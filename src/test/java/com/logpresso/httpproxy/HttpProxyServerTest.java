package com.logpresso.httpproxy;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.After;
import org.junit.Assume;
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

		// the reset client of tunnel Y also closed its upstream side
		assertEquals(-1, y[1].getInputStream().read());

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

	// a client that connects and closes without sending headers (health check, port scan) must be closed, not
	// left registered: OP_READ is level-triggered, so an ignored EOF spins the selector and leaks the channel
	@Test(timeout = 30000)
	public void closesClientThatClosesBeforeHeaders() throws Exception {
		File fdDir = new File("/proc/self/fd");
		Assume.assumeTrue(fdDir.isDirectory());

		// the connect probe in setUp() already closed one client before headers
		assertProxyIdle();
		int baseline = fdDir.list().length;

		for (int i = 0; i < 20; i++) {
			Socket s = new Socket("127.0.0.1", proxyPort);
			s.close();
		}

		long deadline = System.currentTimeMillis() + 5000;
		int count = fdDir.list().length;
		while (count > baseline && System.currentTimeMillis() < deadline) {
			Thread.sleep(50);
			count = fdDir.list().length;
		}
		assertTrue("proxy leaked " + (count - baseline) + " client channels", count <= baseline);
		assertProxyIdle();

		Socket[] t = openTunnel();
		t[1].getOutputStream().write("ping".getBytes("utf-8"));
		assertEquals("ping", readExactly(t[0].getInputStream(), 4));
		t[0].close();
		t[1].close();
	}

	// a client that sends a full CONNECT and closes at once must cause exactly one upstream connect, which is then
	// closed. Re-reading the EOF in the header phase re-parsed the buffered request and connected again on every spin.
	@Test(timeout = 30000)
	public void startsOneUpstreamConnectPerRequest() throws Exception {
		Socket client = new Socket("127.0.0.1", proxyPort);
		String target = "127.0.0.1:" + upstream.getLocalPort();
		String req = "CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n";
		client.getOutputStream().write(req.getBytes("utf-8"));
		client.close();

		Socket server = upstream.accept();
		server.setSoTimeout(5000);
		assertEquals(-1, server.getInputStream().read());
		server.close();

		upstream.setSoTimeout(1000);
		try {
			Socket extra = upstream.accept();
			extra.close();
			throw new AssertionError("proxy started a second upstream connect for one request");
		} catch (SocketTimeoutException e) {
			// expected
		}
		assertProxyIdle();
	}

	// an upstream EOF closes the client side too, so the client does not wait for data that never comes
	@Test(timeout = 30000)
	public void closesClientWhenUpstreamCloses() throws Exception {
		Socket[] t = openTunnel();
		t[0].setSoTimeout(5000);
		t[1].close();
		assertEquals(-1, t[0].getInputStream().read());
		t[0].close();
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

	// fails if the selector thread burns CPU while no connection has pending work
	private void assertProxyIdle() throws InterruptedException {
		ThreadMXBean mx = ManagementFactory.getThreadMXBean();
		Assume.assumeTrue(mx.isThreadCpuTimeSupported());
		if (!mx.isThreadCpuTimeEnabled())
			mx.setThreadCpuTimeEnabled(true);

		// let in-flight work settle before measuring
		Thread.sleep(200);
		long before = mx.getThreadCpuTime(proxyThread.getId());
		Thread.sleep(1000);
		long usedMillis = (mx.getThreadCpuTime(proxyThread.getId()) - before) / 1000000;
		assertTrue("proxy selector thread used " + usedMillis + "ms CPU in 1s while idle", usedMillis < 200);
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
