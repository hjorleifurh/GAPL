package is.ru.cadia.ggp.utils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;

public class IOUtils {
	private static final int BUFFERSIZE = 16*1024;

	public static void copyStream(InputStream input, OutputStream output) throws IOException, InterruptedException {
		byte[] buffer = new byte[BUFFERSIZE];
		int bytesRead;
		while ((bytesRead = input.read(buffer)) != -1) {
			output.write(buffer, 0, bytesRead);
			if (Thread.interrupted()) {
		        throw new InterruptedException();
		    }
		}
	}

	public static void connectStreams(final InputStream input, final OutputStream output) {
		Thread t = new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					copyStream(input, output);
				} catch (IOException e) {
					e.printStackTrace();
				} catch (InterruptedException e) {
				}
			}
		});
		t.setDaemon(true);
		t.start();
	}

	public static boolean runWithDeadline(long deadline, Runnable r) throws InterruptedException {
		long waitTime = deadline - System.currentTimeMillis();
		if (waitTime>0) {
			Thread t = new Thread(r);
			t.start();
			try {
				t.join(waitTime);
			} catch (InterruptedException e) {
				System.err.println("runWithDeadline was interrupted: ");
				e.printStackTrace();
				if (t.isAlive()) t.interrupt();
				throw e;
			}
			if (t.isAlive()) {
				t.interrupt();
				return false;
			} else { // Runnable r did terminate normally before the deadline
				return true;
			}
		}
		return false;
	}

	public static String readFile(File gdlFile) throws IOException {
		StringBuffer sb = new StringBuffer();
		BufferedReader br = null;
		br = new BufferedReader(new FileReader(gdlFile));
		String line;
		while ((line = br.readLine()) != null) {
			line = line.trim();
			sb.append(line + "\n"); // artificial EOLN marker
		}
		br.close();
		return sb.toString();
	}

	public static String getGitRevision() {
		String revision = "unknown";
		try{
			InputStream s = IOUtils.class.getResourceAsStream("/commit-id");
			BufferedReader in = new BufferedReader(new InputStreamReader(s));
			revision = in.readLine();
			in.close();
		}catch( Exception e ) {
			System.err.println("Error finding git revision!");
			e.printStackTrace();
		}
		return revision;
	}
}
