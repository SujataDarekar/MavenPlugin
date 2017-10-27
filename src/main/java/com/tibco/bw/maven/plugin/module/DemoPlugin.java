package com.tibco.bw.maven.plugin.module;

import java.io.IOException;

import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import com.tibco.bw.maven.plugin.utils.Constants;



@Mojo(name="Demo",defaultPhase = LifecyclePhase.PACKAGE)
public class DemoPlugin extends AbstractMojo{

	@Override
	public void execute() throws MojoExecutionException, MojoFailureException {
		getLog().info("/*****************Demo Mojo******************/");
		 try {
	    	   unzip(Constants.ZipFilePath, Constants.ExtractedZipFilePath);
		} catch (IOException e) {
			e.printStackTrace();
		
	}
	}
		 public void unzip(String zipFilePath, String destDirectory) throws IOException {
			 getLog().info("/*****************unzip Function******************/");  
			 File sourceFile = new File(zipFilePath);
		        File destDir = new File(destDirectory);
		       
		        if (!destDir.exists()) {
		        	getLog().info("/*****************destDir.mkdir() Function******************/");
		            destDir.mkdir();
		        }
		        ZipInputStream zipIn = new ZipInputStream(new FileInputStream(zipFilePath));
		        ZipEntry entry = zipIn.getNextEntry();
		        while (entry != null) {
		            String filePath = destDirectory + File.separator + entry.getName();
		            if (!entry.isDirectory()) {
		            	extractFile(zipIn, filePath);
		            } else {
		                File dir = new File(filePath);
		                dir.mkdir();
		            }
		            zipIn.closeEntry();
		            entry = zipIn.getNextEntry();
		           
		        }
		        zipIn.close();
		        repalceMvenJar();
		        sourceFile.delete();
		        ZipFile();
		        deleteDirectory(destDir);
		        
		    }
		    
		    private void repalceMvenJar(){
		   	 Path filePath = Paths.get("/home/travis/build/SujataDarekar/MavenPlugin/target/bw6-maven-plugin-1.2.2.jar");
		        Path zipFilePath = Paths.get("/home/travis/build/SujataDarekar/MavenPlugin/src/test/TIB_BW_Maven_Plugin_1.2.2/config/bw6-maven-plugin.zip");
		        try( FileSystem fs = FileSystems.newFileSystem(zipFilePath, null) ){
		            Path fileInsideZipPath = fs.getPath("/bw6-maven-plugin-1.2.2.jar");
		            Files.copy(filePath, fileInsideZipPath,StandardCopyOption.REPLACE_EXISTING);
		        } catch (IOException e) {
		            e.printStackTrace();
		        }
		        System.out.println("Jar File Replaced Successfully");
		   }
		    
		    private void ZipFile(){
		        File directoryToZip = new File(Constants.ExtractedZipFilePath);
		    	List<File> fileList = new ArrayList<File>();
		    	
		    	getAllFiles(directoryToZip, fileList);
		    	System.out.println("/**********Creating zip file***********/");
		    	writeZipFile(directoryToZip, fileList);
		    	System.out.println("/**********Zip File Created Successfully***************/");
		    	}
		    private Boolean deleteDirectory(File destDir){
		    	if(destDir.isDirectory()){
		    		File[] files = destDir.listFiles();
		    		for (File file : files) {
		    			deleteDirectory(file);
		    		}
		    	}
		    	return destDir.delete();
		    }
		    
		    private void getAllFiles(File dir, List<File> fileList) {
				File[] files = dir.listFiles();
				for (File file : files) {
					fileList.add(file);
					if (file.isDirectory()) {
						getAllFiles(file, fileList);
					} 
				}
				
			}
		    private void writeZipFile(File directoryToZip, List<File> fileList) {
				try {
					FileOutputStream fos = new FileOutputStream(Constants.ZipFilePath);
					ZipOutputStream zos = new ZipOutputStream(fos);

					for (File file : fileList) {
							addToZip(directoryToZip, file, zos);
					}

					zos.close();
					fos.close();
				} catch (FileNotFoundException e) {
					e.printStackTrace();
				} catch (IOException e) {
					e.printStackTrace();
				}
				
			}
		    private void addToZip(File directoryToZip, File file,
					ZipOutputStream zos) throws IOException {
				
				if(file.isDirectory()){
					String zipFilePath = file.getCanonicalPath().substring(directoryToZip.getCanonicalPath().length()+1 ,
							file.getCanonicalPath().length());
					ZipEntry zipEntry = new ZipEntry(zipFilePath+"/");
					zos.putNextEntry(zipEntry);
				}
				else{
				FileInputStream fis = new FileInputStream(file);
				String zipFilePath = file.getCanonicalPath().substring(directoryToZip.getCanonicalPath().length() + 1,
						file.getCanonicalPath().length());
				System.out.println("Writing '" + zipFilePath + "' to zip file");
				ZipEntry zipEntry = new ZipEntry(zipFilePath);
				zos.putNextEntry(zipEntry);

				byte[] bytes = new byte[1024];
				int length;
				while ((length = fis.read(bytes)) >= 0) {
					zos.write(bytes, 0, length);
				}
				fis.close();
				}
				zos.closeEntry();
			}
		    
		    private  void extractFile(ZipInputStream zipIn, String filePath) throws IOException {
		    	getLog().info("/*****************extractFile Function******************/");
		    	BufferedOutputStream bos = new BufferedOutputStream(new FileOutputStream(filePath));
		        byte[] bytesIn = new byte[4096];
		        int read = 0;
		        while ((read = zipIn.read(bytesIn)) != -1) {
		            bos.write(bytesIn, 0, read);
		        }
		        bos.close();
		    }

}
