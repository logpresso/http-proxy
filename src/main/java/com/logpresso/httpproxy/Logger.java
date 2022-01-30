package com.logpresso.httpproxy;

public interface Logger {
	void setDebug(boolean enabled);

	boolean isDebugEnabled();

	void debug(String msg);

	void info(String msg);
	
	void warn(String msg);

	void error(String msg);

}
