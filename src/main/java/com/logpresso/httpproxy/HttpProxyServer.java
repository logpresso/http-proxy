package com.logpresso.httpproxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Set;

public class HttpProxyServer {
	// request headers larger than this are rejected instead of buffered without limit
	static final int MAX_HEADER_SIZE = 16 * 1024;

	private Logger logger = new ConsoleLogger();
	private Configuration conf;

	public static void main(String[] args) throws IOException {
		java.security.Security.setProperty("networkaddress.cache.ttl", "30");

		if (args.length == 0) {
			System.out.println("Logpresso HTTP proxy 1.1.2 (2026-11-12)");
			System.out.println("Usage: logpresso-http-proxy [start|install|uninstall]");
			return;
		}

		String mode = args[0];

		if ("start".equals(mode)) {
			Configuration c = Configuration.load();
			new HttpProxyServer().run(c);
		} else if ("install".equals(mode)) {
			Configuration.install();
		} else if ("uninstall".equals(mode)) {
			Configuration.uninstall();
		}
	}

	public void run(Configuration conf) throws IOException {
		this.conf = conf;

		logger.setDebug(conf.isDebug());

		Selector selector = Selector.open();
		ServerSocketChannel serverSocket = null;
		try {
			serverSocket = ServerSocketChannel.open();
			serverSocket.bind(new InetSocketAddress(conf.getPort()));
			serverSocket.configureBlocking(false);
			serverSocket.register(selector, SelectionKey.OP_ACCEPT);

			logger.info("Listening on " + conf.getPort() + " port..");

			while (true) {
				selector.select();
				Set<SelectionKey> selectedKeys = selector.selectedKeys();
				for (SelectionKey key : selectedKeys) {
					// handling another key in this set may have closed this key's channel as its peer
					if (!key.isValid())
						continue;

					// read() and connect() close their own connection pair on any error
					if (key.isAcceptable()) {
						accept(selector, (ServerSocketChannel) key.channel());
					} else if (key.isReadable()) {
						read(selector, key);
					} else if (key.isConnectable()) {
						connect(selector, key);
					}
				}

				selectedKeys.clear();
			}
		} finally {
			if (serverSocket != null)
				serverSocket.close();
		}
	}

	private void accept(Selector selector, ServerSocketChannel serverSocket) throws IOException {
		SocketChannel client = serverSocket.accept();
		if (client != null) {
			client.configureBlocking(false);
			SelectionKey key = client.register(selector, SelectionKey.OP_READ);
			key.attach(new Context());
		}
	}

	private void connect(Selector selector, SelectionKey key) {
		SocketChannel channel = (SocketChannel) key.channel();
		Context ctx = (Context) key.attachment();
		try {
			if (channel.finishConnect()) {
				key.interestOps(key.interestOps() & ~SelectionKey.OP_CONNECT);
				SelectionKey readKey = channel.register(selector, SelectionKey.OP_READ);
				readKey.attach(ctx);

				// resume reading the client, paused while the upstream connect was pending
				ctx.peerChannel.keyFor(selector).interestOps(SelectionKey.OP_READ);

				logger.info(ctx.peerChannel.getRemoteAddress() + " is connected to " + channel.getRemoteAddress());

				// set connected channel to client context
				ctx.peerContext.peerChannel = channel;

				if (ctx.forwardData != null) {
					// For HTTP: forward client's original request to server
					ensureWrite(channel, ByteBuffer.wrap(ctx.forwardData.getBytes("utf-8")));
				} else {
					// For HTTPS: send connect success response to client
					String resp = ctx.httpVersion + " 200 Connection Established\r\nConnection: close\r\n\r\n";
					ByteBuffer bb = ByteBuffer.wrap(resp.getBytes());
					ensureWrite(ctx.peerChannel, bb);
				}
			}
		} catch (Throwable t) {
			logger.error("cannot finish connect " + ctx.endpoint + " (" + t.toString() + ")");
			closePair(key);
		}
	}

	public void read(Selector selector, SelectionKey key) {
		Context ctx = (Context) key.attachment();
		SocketChannel channel = (SocketChannel) key.channel();

		try {
			if (!ctx.isRemote() && ctx.peerChannel == null) {
				ByteBuffer bb = ByteBuffer.wrap(ctx.temp);
				int len = channel.read(bb);
				if (len < 0) {
					// client closed before sending complete headers; OP_READ is level-triggered and would fire forever
					logger.debug("Client closed before sending request headers");
					channel.close();
					return;
				}

				if (len > 0)
					ctx.buffer.write(ctx.temp, 0, len);

				if (ctx.buffer.size() > MAX_HEADER_SIZE) {
					logger.warn("Rejected request headers over " + MAX_HEADER_SIZE + " bytes from " + channel.getRemoteAddress());
					String msg = "HTTP/1.1 431 Request Header Fields Too Large\r\nConnection: close\r\n\r\n";
					ensureWrite(channel, ByteBuffer.wrap(msg.getBytes("utf-8")));
					channel.close();
					return;
				}

				String headers = ctx.buffer.toString("utf-8");
				if (headers.endsWith("\r\n\r\n")) {
					ConnectRequest req = ConnectRequest.parse(headers);
					if (!conf.getAllowlist().isEmpty() && !conf.getAllowlist().contains(req.getRequestTarget())) {
						logger.warn("Rejected connect " + req.getEndpoint() + " request from " + channel.getRemoteAddress());
						String msg = req.getHttpVersion() + " 403 Forbidden\r\nConnection: close\r\n\r\n";

						ensureWrite(channel, ByteBuffer.wrap(msg.getBytes("utf-8")));
						channel.close();
						return;
					}

					logger.debug("Trying to connect " + req.getEndpoint());

					String forwardData = req.getHttpMethod().equalsIgnoreCase("CONNECT") ? null : headers;
					Context remoteCtx = new Context(channel, ctx, req.getEndpoint(), req.getHttpVersion(), forwardData);

					SocketChannel remote = SocketChannel.open();
					try {
						remote.configureBlocking(false);
						remote.connect(req.getEndpoint());
						remote.register(selector, SelectionKey.OP_CONNECT, remoteCtx);
					} catch (IOException | RuntimeException e) {
						// e.g. UnresolvedAddressException for an unknown host
						ensureClose(remote);
						throw e;
					}

					// stop reading the client until connect() finishes, so the parsed request starts only one connect
					key.interestOps(0);
				}
			} else {
				ByteBuffer bb = ByteBuffer.wrap(ctx.temp);
				int len = channel.read(bb);
				if (len > 0) {
					// cast to Buffer: JDK 9+ links ByteBuffer.flip()ByteBuffer, which does not exist on Java 7/8
					((Buffer) bb).flip();
					ensureWrite(ctx.peerChannel, bb);
					logger.debug("Received " + len + " bytes from " + channel.getRemoteAddress() + ", sent to "
							+ ctx.peerChannel.getRemoteAddress());
				} else {
					logger.debug("Read len " + len + " from " + channel.getRemoteAddress());
					closePair(key);
				}
			}
		} catch (Throwable t) {
			try {
				logger.debug("Read error from channel: " + channel.getRemoteAddress() + " - " + t.toString());
			} catch (IOException e) {
				// channel already closed; the original error is logged without the address
				logger.debug("Read error from channel: " + t.toString());
			}

			closePair(key);
		}
	}

	private void ensureWrite(SocketChannel channel, ByteBuffer bb) throws IOException {
		long start = 0;
		while (bb.hasRemaining()) {
			channel.write(bb);

			if (bb.hasRemaining()) {
				long now = System.currentTimeMillis();
				if (start == 0) {
					start = now;
				} else if (now - start >= 5000) {
					channel.close();
					break;
				}
			}
		}
	}

	private static class Context {
		private SocketChannel peerChannel;
		private Context peerContext;
		private InetSocketAddress endpoint;
		private String httpVersion;
		private String forwardData;
		private byte[] temp = new byte[8192];
		private ByteArrayOutputStream buffer = new ByteArrayOutputStream();

		public Context() {
		}

		public Context(SocketChannel peer, Context peerContext, InetSocketAddress endpoint, String httpVersion,
				String forwardData) {
			this.peerChannel = peer;
			this.peerContext = peerContext;
			this.endpoint = endpoint;
			this.httpVersion = httpVersion;
			this.forwardData = forwardData;
		}

		public boolean isRemote() {
			return peerContext != null;
		}
	}

	private void closePair(SelectionKey key) {
		Context ctx = (Context) key.attachment();
		if (ctx != null)
			ensureClose(ctx.peerChannel);
		ensureClose((SocketChannel) key.channel());
	}

	private void ensureClose(SocketChannel c) {
		if (c != null) {
			try {
				c.close();
			} catch (IOException e) {
			}
		}
	}
}
