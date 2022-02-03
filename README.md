![Logpresso Logo](logo.png)

Logpresso HTTP Proxy is a single binary command-line tool for HTTP proxy server. If your firewall policy restricts arbitrary outbound access to the internet, you can use this HTTP proxy server to relay Logpresso Watch traffic. Of course, you can also use this HTTP proxy for generic purpose.

### Usage
```
Usage: logpresso-http-proxy [start|install|uninstall]
```

### Getting Started
* Install Logpresso HTTP proxy as systemd service.
  * `# ./logpresso-http-proxy install`
  ```
  Wrote 91 bytes to /home/xeraph/logpresso-http-proxy.conf
  Wrote 291 bytes to /lib/systemd/system/logpresso-http-proxy.service
  ```
* Review logpresso-http-proxy.conf file.
  * Default proxy server port is 8443.
  * If you want to restrict HTTP traffic to certain web services, specify `[allowlist]`
  * For example, if you want to relay only logpresso.watch service traffic:
    ```
    [allowlist]
    # host:port
    logpresso.watch:443
    ```
    * Allowlist should match request target of HTTP CONNECT method.
    * You should write also port number.
* Start systemd service.
  * `# systemctl start logpresso-http-proxy`
* Monitor HTTP proxy traffic.
  * You can tail logs in realtime using `# journalctl -u logpresso-http-proxy -f`
  * For allowed traffic: `[ INFO] /172.20.x.x:64428 is connected to logpresso.watch/3.36.184.203:443`
  * For rejected traffic: `[ WARN] Rejected connect ssl.gstatic.com/172.217.175.3:443 request from /172.20.x.x:63358`

### Uninstall
* Stop systemd service firfst.
  * `# systemctl stop logpresso-http-proxy`
* Run Logpresso HTTP Proxy with uninstall option.
  * `# ./logpresso-http-proxy uninstall`
  * It will delete systemd file and reload systemd daemon.

  
### Contact
If you have any question or issue, create an issue in this repository.
