package demoplugin;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Demo {

	public static void main(String[] args) throws FileNotFoundException {
		// TODO Auto-generated method stub
      System.out.println("In Demo");
      StringBuilder sb = new StringBuilder();
      sb.append("Test String");

      File f = new File("d:\\test.zip");
      ZipOutputStream out = new ZipOutputStream(new FileOutputStream(f));
      ZipEntry e = new ZipEntry("mytext.txt");
      try {
		out.putNextEntry(e);
	} catch (IOException e1) {
		// TODO Auto-generated catch block
		e1.printStackTrace();
	}

      byte[] data = sb.toString().getBytes();
      try {
		out.write(data, 0, data.length);
	} catch (IOException e1) {
		// TODO Auto-generated catch block
		e1.printStackTrace();
	}
      try {
		out.closeEntry();
	} catch (IOException e1) {
		// TODO Auto-generated catch block
		e1.printStackTrace();
	}

      try {
		out.close();
	} catch (IOException e1) {
		// TODO Auto-generated catch block
		e1.printStackTrace();
	}
	}

}
