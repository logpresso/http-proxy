package com.logpresso.httpproxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HttpProxyServerTest {
	private ServerSocket upstream;
	private int proxyPort;
	private Thread proxyThread;
	private final AtomicReference<Throwable> proxyFailure = new AtomicReference<Throwable>();

	@Before
	public void setUp() throws Exception {
		upstream = new ServerSocket(0, 50, java.net.InetAddress.getLoopbackAddress());

		ServerSocket probe = new ServerSocket(0);
		proxyPort = probe.getLocalPort();
		probe.close();

		final Configuration conf = new Configuration();
		Field port = Configuration.class.getDeclaredField("port");
		port.setAccessible(true);
		port.setInt(conf, proxyPort);

		proxyThread = new Thread("http-proxy-test") {
			@Override
			public void run() {
				try {
					new HttpProxyServer().run(conf);
				} catch (Throwable t) {
					proxyFailure.set(t);
				}
			}
		};
		proxyThread.setDaemon(true);
		proxyThread.start();
		waitForProxy();
	}

	@After
	public void tearDown() throws IOException {
		upstream.close();
	}

	// WISH-1217: closing a peer channel while handling one key cancels the peer's key. If that key is in the
	// same selected set, readyOps() threw CancelledKeyException out of the selector loop and the process exited.
	@Test(timeout = 30000)
	public void survivesPeerKeyCancelledInSameSelectedSet() throws Exception {
		Socket[] y = openTunnel();
		Socket[] x = openTunnel();

		// stall the selector thread: the proxy keeps writing to client X, which does not read
		final OutputStream floodOut = x[1].getOutputStream();
		Thread flood = new Thread("flood") {
			@Override
			public void run() {
				byte[] chunk = new byte[65536];
				try {
					for (int i = 0; i < 1024; i++)
						floodOut.write(chunk);
				} catch (IOException e) {
					// expected when the tunnel is closed
				}
			}
		};
		flood.setDaemon(true);
		flood.start();
		Thread.sleep(1000);

		// while the selector is stalled, make both ends of tunnel Y ready at once: data from upstream, RST from client
		y[1].getOutputStream().write("hello".getBytes("utf-8"));
		y[1].getOutputStream().flush();
		y[0].setSoLinger(true, 0);
		y[0].close();
		Thread.sleep(300);

		// release the stall; the next select returns both keys of tunnel Y in one selected set
		x[0].setSoLinger(true, 0);
		x[0].close();
		Thread.sleep(1000);

		assertNull("proxy loop died: " + proxyFailure.get(), proxyFailure.get());
		assertTrue(proxyThread.isAlive());

		// the proxy still relays new tunnels
		Socket[] z = openTunnel();
		z[1].getOutputStream().write("ping".getBytes("utf-8"));
		assertEquals("ping", readExactly(z[0].getInputStream(), 4));
		z[0].getOutputStream().write("pong".getBytes("utf-8"));
		assertEquals("pong", readExactly(z[1].getInputStream(), 4));

		z[0].close();
		z[1].close();
		y[1].close();
		x[1].close();
	}

	// a failed upstream connect closes the client connection instead of leaving it waiting
	@Test(timeout = 30000)
	public void closesClientWhenUpstreamConnectFails() throws Exception {
		ServerSocket closed = new ServerSocket(0);
		int closedPort = closed.getLocalPort();
		closed.close();

		Socket client = new Socket("127.0.0.1", proxyPort);
		client.setSoTimeout(5000);
		String target = "127.0.0.1:" + closedPort;
		String req = "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n";
		client.getOutputStream().write(req.getBytes("utf-8"));

		assertEquals(-1, client.getInputStream().read());
		client.close();
		assertNull(proxyFailure.get());
	}

	// returns { client socket, upstream-side socket }
	private Socket[] openTunnel() throws IOException {
		Socket client = new Socket("127.0.0.1", proxyPort);
		client.setSoTimeout(10000);
		String target = "127.0.0.1:" + upstream.getLocalPort();
		String req = "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n";
		client.getOutputStream().write(req.getBytes("utf-8"));

		Socket server = upstream.accept();
		server.setSoTimeout(10000);

		String resp = readHeaders(client.getInputStream());
		assertTrue(resp, resp.startsWith("HTTP/1.1 200 "));
		return new Socket[] { client, server };
	}

	private void waitForProxy() throws Exception {
		for (int i = 0; i < 100; i++) {
			assertNull(proxyFailure.get());
			Socket s = new Socket();
			try {
				s.connect(new InetSocketAddress("127.0.0.1", proxyPort), 100);
				return;
			} catch (IOException e) {
				Thread.sleep(50);
			} finally {
				s.close();
			}
		}
		throw new IllegalStateException("proxy did not start");
	}

	private static String readHeaders(InputStream is) throws IOException {
		ByteArrayOutputStream bos = new ByteArrayOutputStream();
		while (true) {
			int b = is.read();
			if (b < 0)
				break;
			bos.write(b);
			if (bos.toString("utf-8").endsWith("\r\n\r\n"))
				break;
		}
		return bos.toString("utf-8");
	}

	private static String readExactly(InputStream is, int len) throws IOException {
		byte[] b = new byte[len];
		int off = 0;
		while (off < len) {
			int n = is.read(b, off, len - off);
			if (n < 0)
				throw new IOException("unexpected eof");
			off += n;
		}
		return new String(b, "utf-8");
	}
}
