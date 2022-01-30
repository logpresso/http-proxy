package com.logpresso.httpproxy;

import java.io.*;

public class AttachScriptAndJar {
	public static void main(String[] args) {
		File scriptFile = new File(
				new File(System.getProperty("attach_script_and_jar.base_dir")),
				System.getProperty("attach_script_and_jar.input_script"));
		
		File jarFile = new File(
				new File(System.getProperty("attach_script_and_jar.target_dir")),
				System.getProperty("attach_script_and_jar.input_jar"));
				
		if (!scriptFile.exists())
			throw new IllegalArgumentException("input_script does not exists: " + scriptFile.getAbsolutePath());

		if (!jarFile.exists())
			throw new IllegalArgumentException("input_jar does not exists: " + jarFile.getAbsolutePath());

		File outputFile = new File(
				new File(System.getProperty("attach_script_and_jar.target_dir")),
				System.getProperty("attach_script_and_jar.output_name"));
		
		FileOutputStream fos = null;
		FileInputStream fis1 = null, fis2 = null;	
		try {
			System.out.println("input script: " + scriptFile.getAbsolutePath());
			System.out.println("input JAR   : " + jarFile.getAbsolutePath());
			
			fos = new FileOutputStream(outputFile, false);
			fis1 = new FileInputStream(scriptFile);
			fis2 = new FileInputStream(jarFile);
			
			byte[] buf = new byte[32768];
			for (int read = fis1.read(buf); read != -1; read = fis1.read(buf)) {
				fos.write(buf, 0, read);
			}
			for (int read = fis2.read(buf); read != -1; read = fis2.read(buf)) {
				fos.write(buf, 0, read);
			}
			
			System.out.println("** Successfully merged script and jar: " + outputFile.getAbsolutePath());
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} catch (IOException e) {
			e.printStackTrace();
		} finally {
			close(fos);
			close(fis1);
			close(fis2);
		}
	}
	
	private static void close(Closeable c) {
		if (c != null) {
			try {
				c.close();
			} catch (Exception ignored) {
			}
		}
	}
}
