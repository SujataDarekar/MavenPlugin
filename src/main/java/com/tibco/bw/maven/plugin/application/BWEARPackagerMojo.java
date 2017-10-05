package com.tibco.bw.maven.plugin.application;

import java.io.File;
import java.io.FileFilter;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;

import org.apache.commons.io.FileUtils;
import org.apache.maven.archiver.MavenArchiveConfiguration;
import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.codehaus.plexus.archiver.jar.JarArchiver;
import org.eclipse.aether.graph.Dependency;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.tibco.bw.maven.plugin.osgi.helpers.ManifestParser;
import com.tibco.bw.maven.plugin.osgi.helpers.ManifestWriter;
import com.tibco.bw.maven.plugin.osgi.helpers.Version;
import com.tibco.bw.maven.plugin.osgi.helpers.VersionParser;
import com.tibco.bw.maven.plugin.utils.BWModulesParser;
import com.tibco.bw.maven.plugin.utils.BWProjectUtils;
import com.tibco.bw.maven.plugin.utils.Constants;

@Mojo(name = "bwear", defaultPhase = LifecyclePhase.PACKAGE)
public class BWEARPackagerMojo extends AbstractMojo {
	@Parameter(property="project.build.directory")
    private File outputDirectory;

	@Parameter(property="project.basedir")
	private File projectBasedir;

    @Component
    private MavenSession session;

    @Component
    private MavenProject project;

    @Component
    ProjectDependenciesResolver resolver;
    
    private List<File> tempFiles;

    private Manifest manifest;

    //This is the actual JAR file which will be created in the EAR file.
    JarArchiver jarchiver;

    //This will create the EAR file
    MavenArchiver archiver;

    //Archive Configuration. This will set the Configuration for the Archive.
    protected MavenArchiveConfiguration archiveConfiguration;

    //This map is required for maintaining the module name vs module version which needs to be updated in the TibcoXML at a later stage.    
    Map<String, String> moduleVersionMap;

    //The version to be updated in the Application Manifest. 
    String version;

    protected String pluginsToIgnore[] = null;
    
    //list of properties which needs to be apply to components of an application
    public static ArrayList<String> componentPropertylist;
    //mapping of property versus IsComponentSpecific
    public static HashMap<String,Boolean> componentPropertyMap;
    
    Boolean isComponenetSpecific;
    
    static{
    	componentPropertylist=new ArrayList<>();
		componentPropertylist.add(Constants.PROPERTY_PRIORITY);
		componentPropertylist.add(Constants.PROPERTY_FLOWLIMIT);
		componentPropertylist.add(Constants.PROPERTY_PAGETHRESHOLD);
		componentPropertylist.add(Constants.PROPERTY_RETAINFAULTEDJOB);
		componentPropertylist.add(Constants.PROPERTY_RECOVERONRESTART);
				
		componentPropertyMap=new HashMap<>();
		componentPropertyMap.put(Constants.PROPERTY_PRIORITY,true);
		componentPropertyMap.put(Constants.PROPERTY_FLOWLIMIT,true);
		componentPropertyMap.put(Constants.PROPERTY_PAGETHRESHOLD,true);
		componentPropertyMap.put(Constants.PROPERTY_RETAINFAULTEDJOB,false);
		componentPropertyMap.put(Constants.PROPERTY_RECOVERONRESTART,false);
    }
    /**
     * Execute Method.
     * 
     */
    public void execute() throws MojoExecutionException {
    	try {
    		getLog().info("BWEARPackager Mojo started ...");
    	    tempFiles = new ArrayList<File>();
    	    jarchiver = new JarArchiver();
    	    archiver = new MavenArchiver();
    	   
    	    archiveConfiguration = new MavenArchiveConfiguration();
    	    moduleVersionMap = new HashMap<String, String>();
            manifest = ManifestParser.parseManifest(projectBasedir);
            File manifestFile = ManifestWriter.updateManifest(project, manifest);
            getLog().info("Updated the Manifest version ");
            updateManifestVersion();
    	    getLog().info("Adding Modules to the EAR file");
    		addModules();
    		getLog().info("Adding EAR Information to the EAR File");
    		addApplication();
    		cleanup();
    		getLog().info("BWEARPackager Mojo finished execution");
		} catch (Exception e1) {
			throw new MojoExecutionException("Failed to create BW EAR Archive ", e1);
		}
	}

	/**
	 * Add the Application related files to the EAR.
	 * 
	 * @throws Exception
	 */
	private void addApplication() throws Exception {
		getLog().debug("Adding Application specific files...");
		// Get the META-INF Folder for the Application Project
		File metainfFolder = getApplicationMetaInf();

		//Add the files from the META-INF to the EAR File.
		File manifestFile = ManifestWriter.updateManifest(project, manifest);
		File appManifest = addFiletoEAR(metainfFolder);

		File earFile = getArchiveFileName();
		archiver.setArchiver(jarchiver);

		archiver.setOutputFile(earFile);

		// Set the MANIFEST.MF to the JAR Archiver
		jarchiver.setManifest(appManifest);

		// Set the MANIFEST.MF to the Archive Configuration
		archiveConfiguration.setManifestFile(appManifest);

		archiveConfiguration.setAddMavenDescriptor(true);

		//Create the Archive.
		archiver.createArchive(session, project, archiveConfiguration);

		project.getArtifact().setFile(earFile);

		//Move Archive
		//TODO: Need to move the Archive to the User defined location.
	}    

    /**
     * Adds the Modules included in the Application to the EAR file. 
     * It will also maintain a Module vs Version map which will be used later by the Application 
     * to populate the TIBCO.xml
     *  
     * @throws Exception
     */
    private void addModules() throws Exception {
    	try {
        	getLog().debug("Adding Modules to the Application EAR");
        	// The first artifact is an Application Module
        	//boolean isAppModuleArtifact = true;

        	// Use manifest of project.basedir
        	this.version = manifest.getMainAttributes().getValue(Constants.BUNDLE_VERSION);

        	BWModulesParser parser = new BWModulesParser(session, project);
        	String bwEdition = manifest.getMainAttributes().getValue(Constants.TIBCO_BW_EDITION);
        	parser.bwEdition = bwEdition;
        	List<Artifact> artifacts = parser.getModulesSet();

       	
       	
            for(Artifact artifact : artifacts) {
                //Find the Module JAR file
                File moduleJar = artifact.getFile();

                //Add the JAR file to the EAR file
                jarchiver.addFile(moduleJar, moduleJar.getName());
                String version = BWProjectUtils.getModuleVersion(moduleJar);
                getLog().info("Adding Module JAR with name " + moduleJar.getName() + " with version " + version);

                //Save the module version in the Version Map.
                moduleVersionMap.put(artifact.getArtifactId(), version);
                /*if(isAppModuleArtifact) {
                	this.version = version;
                	isAppModuleArtifact = false;
                }*/
                
              
            }
            
    		List<MavenProject> projects = parser.getModulesProjectSet();
    		for(MavenProject project : projects){
    			
    			Set<Artifact> dependencyArtifacts = project.getDependencyArtifacts();
    			Set<File> artifactFiles = new HashSet<File>(); 

    			for(Artifact artifact : dependencyArtifacts) {
    				if(artifact.getVersion().equals("0.0.0")) { //$NON-NLS-1$
    					continue;
    				}
    				
    				if(moduleVersionMap.containsKey(artifact.getArtifactId())){
    					continue;
    				}
    				
					artifactFiles.add(artifact.getFile());
    			}

    			//This code allows dependencies delared in a Module to make it to the root level of the ear file
    			//This is necessary for the ear file to run properly
    	        DependencyResolutionResult resolutionResult = getDependenciesResolutionResult();

    	        if (resolutionResult != null) {
    	        	for(Dependency dependency : resolutionResult.getDependencies()) {
    	    			if(dependency.getArtifact().getVersion().equals("0.0.0")) { //$NON-NLS-1$
    	    				continue;
    	    			}
    	    			
    	    			if(moduleVersionMap.containsKey(dependency.getArtifact().getArtifactId())){
    	    				continue;
    	    			}
    	    			
    	                String dependencyVersion = BWProjectUtils.getModuleVersion(dependency.getArtifact().getFile());
    	                moduleVersionMap.put(dependency.getArtifact().getArtifactId(), dependencyVersion);
    					artifactFiles.add(dependency.getArtifact().getFile());
    	        	}
    	        }  
    	        
    			for(File file : artifactFiles) {
    				if(isPluginToIgnore(file.getName())){//if(file.getName().indexOf("com.tibco.bw.palette.shared") != -1 || file.getName().indexOf("com.tibco.xml.cxf.common") != -1 || file.getName().indexOf("tempbw") != -1) {
    					continue;
    				}
    				jarchiver.addFile(file, file.getName());
    			}
    		}
    	} catch(Exception e) {
    		getLog().error("Failed to add modules to the Application");
    		throw e;
    	}
    }
    
	private DependencyResolutionResult getDependenciesResolutionResult() {
		DependencyResolutionResult resolutionResult = null;
        try {
        	getLog().debug("Looking up dependency tree for the current project => " +  project + " and the current session => " + session);
            DefaultDependencyResolutionRequest resolution = new DefaultDependencyResolutionRequest(project, session.getRepositorySession());
            resolutionResult = resolver.resolve(resolution);
        } catch (DependencyResolutionException e) {
        	getLog().debug("Caught DependencyResolutionException for the project => " + e.getMessage() + " with cause => " + e.getCause());
        	e.printStackTrace();
            resolutionResult = e.getResult();
        }
		return resolutionResult;
	}
    
    protected String[] getPluginsToIgnore(){
    	if(pluginsToIgnore == null){
    		pluginsToIgnore = new String[]{
    				"com.tibco.bw.palette.shared",
    				"com.tibco.xml.cxf.common",
    				"tempbw"
    		};
    	}
    	
    	return pluginsToIgnore;
    }
    
    protected boolean isPluginToIgnore(String pluginName){
    	for(String toIgnore : getPluginsToIgnore()){
    		if(pluginName.startsWith(toIgnore)){
    			return true;
    		}
    	}
    	
    	return false;
    }

	/**
	 * Returns the Archive file name and location. The Archive file is created in the Target directory 
	 * with the name same as application project which is also the artifactId for the Application project. 
	 * @return
	 */
	private File getArchiveFileName() {
		Version version = VersionParser.parseVersion(manifest.getMainAttributes().getValue(Constants.BUNDLE_VERSION));
		String fullVersion = version.getMajor() + "." + version.getMinor() + "." + version.getMicro();

        String archiveName = project.getArtifactId() + "_" + fullVersion + ".ear";
        File archiveFile = new File(outputDirectory, archiveName);

        getLog().info("The EAR file name for Application is " + archiveFile.toString());
        return archiveFile;
	}

    /**
     * Adds the from the META-INF folder to the EAR file.
     * The META-INF folder needs to be copied as it is.
     * 
     * @param metainf the META-INF folder location for the Application project.
     * 
     * @return the NANIFEST.MF file for the Application project.
     * 
     * @throws Exception
     */
	private File addFiletoEAR(File metainf) throws Exception {
		File manifestFile = null;
	    File [] fileList = metainf.listFiles();
	    getLog().debug("Adding files to META-INF folder of EAR. ");
	    for(int i = 0; i < fileList.length; i++) {
	    	if(fileList[i].getName().indexOf("MANIFEST") != -1) { // If the File is MANIFEST.MF then the Version needs to be updated in the File and added to the Archiver
    		   manifestFile = getUpdatedManifest (fileList[i]);
    		   jarchiver.addFile(manifestFile, "META-INF/" + fileList[i].getName());
    	    } else if(fileList[i].getName().indexOf("TIBCO.xml") != -1) { // If the File is TIBCO.xml then the each Module Version needs to be updated in the File.
    	    	File tibcoXML = getUpdatedTibcoXML(fileList[i]);
    	    	jarchiver.addFile(tibcoXML, "META-INF/" + fileList[i].getName());
    	    } else if(fileList[i].getName().indexOf(".substvar") != -1) { // The substvar files needs to update to add dynamic properties.
    	    	File substvar = getUpdatedSubstvarFile(fileList[i]);
    	    	jarchiver.addFile(substvar, "META-INF/" + fileList[i].getName());
    	    } else { // The rest of the files can be ignored.
    	    	continue;
    	    }
	    }
	    return manifestFile;
	}

	/**
	 * Updates the MANIFEST.MF with the Module Version number.
	 * 
	 * @param manifest the MANIFEST.MF file
	 * 
	 * @return the updated MANIFEST.MF file
	 * 
	 * @throws Exception
	 */
	private File getUpdatedManifest(File manifest) throws Exception {
		//Copy the MANIFEST.MF to a temporary location.
		File tempManifest = File.createTempFile("bwear", "mf");
		FileUtils.copyFile(manifest, tempManifest);

		FileInputStream is = new FileInputStream(tempManifest);
		Manifest mf = new Manifest(new FileInputStream(tempManifest));
		is.close();

		// Update the Bundle Version
		Attributes attr = mf.getMainAttributes();
		attr.putValue(Constants.BUNDLE_VERSION, version);
		getLog().info("Manifest updated with Version " + version);

		//Write the updated file and return the same.
		FileOutputStream os = new FileOutputStream(tempManifest);
		mf.write(os);
		os.close();

		tempFiles.add(tempManifest);
		getLog().debug("Manifest added to temp location at " + tempManifest.toString());
		return tempManifest;
	}

    /**
     * Finds the folder name META-INF inside the Application Project.
     * 
     * @return the META-INF folder
     * 
     * @throws Exception
     */
	private File getApplicationMetaInf() throws Exception {
		File[] fileList = projectBasedir.listFiles(new FileFilter() {
			public boolean accept(File pathname) {
				if(pathname.getName().indexOf("META-INF") != -1) {
        			return true;
				}
				return false;
			}
		});
       return fileList[0];
	}
    
//    /**
//     * Finds the JAR file for the Module.
//     * 
//     * @param target the Module Output directory which is the target directory.
//     * 
//     * @return the Module JAR.
//     * 
//     * @throws Exception
//     */
//	private File getModuleJar(File target) throws Exception {
//      File[] files = BWFileUtils.getFilesForType(target, ".jar");
//      files = BWFileUtils.sortFilesByDateDesc(files);
//      if(files.length == 0) {
//       	throw new Exception("Module is not built yet. Please check your Application PO for the Module entry.");
//      }
//      return files[0];
//	}

	/**
	 * Gets the Tibco XML file with the updated Module versions.
	 * 
	 * @param tibcoxML the Application Project TIBCO.xml file
	 *  
	 * @return the updated TIBCO.xml file.
	 * 
	 * @throws Exception
	 */
	private File getUpdatedTibcoXML(File tibcoxML) throws Exception {
		getLog().debug("Updating the TibcoXML file with the module versions ");
		Document doc = loadTibcoXML(tibcoxML);
		doc = updateTibcoXMLVersion(doc);
		File file = saveTibcoXML(doc);
		return file;
	}
	
	
	/**
	 * Gets the substvar file with the updated Dynamic properties
	 * @param substvar the Application Project .substvar files
	 * @return the updated .substvar files
	 * @throws Exception
	 */
	private File getUpdatedSubstvarFile(File substvar) throws Exception {
		getLog().debug("Updating the substvar file with the module versions ");
		Document doc = loadTibcoXML(substvar);
		File file = saveTibcoXML(doc);
		return file;
	}


	/**
	 * Loads the TibcoXMLfile in a Document object (DOM)
	 * 
	 * @param file the TIBCO.xml file
	 * 
	 * @return the root Document object for the TIBCO.xml file
	 * 
	 * @throws Exception
	 */
	private Document loadTibcoXML(File file) throws Exception {
		DocumentBuilderFactory docFactory = DocumentBuilderFactory.newInstance();
		docFactory.setNamespaceAware(true);
		DocumentBuilder docBuilder = docFactory.newDocumentBuilder();
		Document doc = docBuilder.parse(file);
		getLog().debug("Loaded Tibco.xml file");
		return doc;
	}
	
	/**
	 * Updates the document with the Module Version for the Modules.
	 * 
	 * @param doc the Root document object.
	 * 
	 * @return the Document updated with the Module versions.
	 * 
	 * @throws Exception
	 */
	private Document updateTibcoXMLVersion(Document doc) throws Exception	{
		// The modules are listed under the Modules tag with name as "module"
		NodeList nList = doc.getElementsByTagNameNS(Constants.PACKAGING_MODEL_NAMESPACE_URI, Constants.MODULE);
		for(int i = 0; i < nList.getLength(); i++) {
			Element node = (Element)nList.item(i);
			// The Symbolic name is the Module name. The version for this needs to be updated under the tag technologyVersion 
			NodeList childList = node.getElementsByTagNameNS(Constants.PACKAGING_MODEL_NAMESPACE_URI, Constants.SYMBOLIC_NAME);
			String module = childList.item(0).getTextContent();		
			NodeList technologyVersionList = node.getElementsByTagNameNS(Constants.PACKAGING_MODEL_NAMESPACE_URI, Constants.TECHNOLOGY_VERSION);
			Node technologyVersion = technologyVersionList.item(0);
			//Get the version from the Module from the Map and set it in the Document. 
			technologyVersion.setTextContent(moduleVersionMap.get(module));
		}
		getLog().debug("Updated Module versions in the Tibcoxml file");
		return doc;
	}
	
	/**
	 * Save the TibcoXML file to a temporary file with the new changes.
	 * 
	 * @param doc the root Document
	 * 
	 * @return the updated TIBCO.xml file location
	 * 
	 * @throws Exception
	 */
	private File saveTibcoXML(Document doc) throws Exception {
		File tempXml = File.createTempFile("bwear", "xml");

		doc.getDocumentElement().normalize();
		// Added by sujata
		doc = addDynamicProerties(doc);
		// Changes End
		TransformerFactory transformerFactory = TransformerFactory
				.newInstance();
		Transformer transformer = transformerFactory.newTransformer();

		DOMSource source = new DOMSource(doc);

		StreamResult result = new StreamResult(tempXml);
		transformer.setOutputProperty(OutputKeys.INDENT, "yes");
		transformer.transform(source, result);
		tempFiles.add(tempXml);

		getLog().debug(
				"Updated TibcoXML file to temp location " + tempXml.toString());
		return tempXml;
	}
	
	

	/**
	 * Read the module file to know the number of components in Application and update 
	  the corresponding XML files on the basis of number of component
	 * @param doc
	 * @return updated doc with dynamic properties
	 * @throws Exception
	 */
	private Document addDynamicProerties(Document doc) throws Exception {
		BWModulesParser parser = new BWModulesParser(session, project);
		List<Artifact> artifacts = parser.getModulesSet();
		Artifact artifact = artifacts.get(0);
		DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
		DocumentBuilder builder = factory.newDocumentBuilder();
		Document moduleDoc = builder.parse(projectBasedir.getParent() + "\\"
				+ artifact.getArtifactId() + "\\META-INF\\" + "module.bwm");
		NodeList componentList = moduleDoc.getElementsByTagName(Constants.COMPONENT);

		if (doc.getDocumentURI().contains(".substvar")) {
			doc = updatePropertyForSubstvar(componentPropertylist,
					componentPropertyMap, componentList, doc);

		} else {
			doc = updatePropertyFortibcoXML(componentPropertylist,
					componentPropertyMap, componentList, doc);
		}

		return doc;
	}
	
	/**
	 * Generate the updated tibcoXML files with dynamic properties per component and application
	 * @param componentPropertylist
	 * @param componentPropertyMap
	 * @param componentList
	 * @param doc
	 * @return updated document with dynamic properties
	 * @throws Exception
	 */
	private Document updatePropertyFortibcoXML(ArrayList<String> componentPropertylist,
			HashMap<String, Boolean> componentPropertyMap,
			NodeList componentList, Document doc) throws Exception{
		String packageType = null;
		NodeList proerties = doc.getElementsByTagName("packaging:properties");
		Element rootElement = (Element) proerties.item(0);

		for (int i = 0; i < componentPropertylist.size(); i++) {
			packageType = getPackageType(componentPropertylist, i, true);
			isComponenetSpecific = componentPropertyMap.get(componentPropertylist
					.get(i));
			if (isComponenetSpecific) {
				for (int j = 0; j < componentList.getLength(); j++) {
					Element component = (Element) componentList.item(j);
					doc = generateTextNodesForTibcoXML(rootElement, componentPropertylist,
							componentList, doc, component, i, packageType);
				}
			}
			doc = generateTextNodesForTibcoXML(rootElement, componentPropertylist,
					componentList, doc, null, i, packageType);

		}
		return doc;
	}
	
	/**
	 * write the actual text node data of dynamic properties in .substvar file
	 * @param rootElement
	 * @param componentPropertylist
	 * @param componentList
	 * @param doc
	 * @param component
	 * @param i
	 * @param packageType
	 * @return updated document with text nodes of dynamic properties
	 */
	private Document generateTextNodesForTibcoXML(Element rootElement,
			ArrayList<String> componentPropertylist, NodeList componentList,
			Document doc, Element component, int i, String packageType) {
		Element property = doc.createElement("packaging:property");
		rootElement.appendChild(property);

		Element name = doc.createElement("packaging:name");
		if (null != component) {
			name.appendChild(doc.createTextNode(componentPropertylist.get(i)
					+ "/" + component.getAttribute("name")));
		} else {
			name.appendChild(doc.createTextNode(componentPropertylist.get(i)));
		}
		property.appendChild(name);

		Element type = doc.createElement("packaging:type");
		type.appendChild(doc.createTextNode(packageType));
		property.appendChild(type);

		Element visibility = doc.createElement("packaging:visibility");
		visibility.appendChild(doc.createTextNode("public"));
		property.appendChild(visibility);

		Element scalable = doc.createElement("packaging:scalable");
		scalable.appendChild(doc.createTextNode("true"));
		property.appendChild(scalable);

		Element overrideValue = doc.createElement("packaging:overrideValue");
		overrideValue.appendChild(doc.createTextNode("false"));
		property.appendChild(overrideValue);
		return doc;
	}

	/**
	 * Generate the updated .substvar files with component properties per component and application
	 * @param componentPropertylist
	 * @param componentPropertyMap
	 * @param componentList
	 * @param XMLdoc
	 * @return updated document with dynamic properties
	 */
	private Document updatePropertyForSubstvar(ArrayList<String> componentPropertylist,
			HashMap<String, Boolean> componentPropertyMap,
			NodeList componentList, Document doc) {
		String packageType = null;
		NodeList proerties = doc.getElementsByTagName("globalVariables");
		Element rootElement = (Element) proerties.item(0);

		for (int i = 0; i < componentPropertylist.size(); i++) {
			packageType = getPackageType(componentPropertylist, i, false);
			isComponenetSpecific = componentPropertyMap.get(componentPropertylist
					.get(i));
			if (isComponenetSpecific) {
				for (int j = 0; j < componentList.getLength(); j++) {

					Element component = (Element) componentList.item(j);

					doc = generateTextNodesSubstvar(rootElement,
							componentPropertylist, componentList, doc,
							component, i, packageType);
				}
			}
			doc = generateTextNodesSubstvar(rootElement, componentPropertylist,
					componentList, doc, null, i, packageType);

		}
		return doc;
	}

	/**
	 * write the actual text node data of dynamic properties in .substvar file
	 * @param rootElement
	 * @param componentPropertylist
	 * @param componentList
	 * @param doc
	 * @param component
	 * @param i
	 * @param packageType
	 * @return updated document with text nodes of dynamic properties
	 */
	private Document generateTextNodesSubstvar(Element rootElement,
			ArrayList<String> componentPropertylist, NodeList componentList,
			Document doc, Element component, int i, String packageType) {
		
		Element property = doc.createElement("globalVariable");
		rootElement.appendChild(property);

		Element name = doc.createElement("name");
		if (null != component) {
			name.appendChild(doc.createTextNode(componentPropertylist.get(i)
					+ "/" + component.getAttribute("name")));
		} else {
			name.appendChild(doc.createTextNode(componentPropertylist.get(i)));
		}
		property.appendChild(name);

		Element deploymentSettable = doc.createElement("deploymentSettable");
		deploymentSettable.appendChild(doc.createTextNode("false"));
		property.appendChild(deploymentSettable);

		Element serviceSettable = doc.createElement("serviceSettable");
		serviceSettable.appendChild(doc.createTextNode("false"));
		property.appendChild(serviceSettable);

		Element type = doc.createElement("type");
		type.appendChild(doc.createTextNode(packageType));
		property.appendChild(type);

		Element overrideValue = doc.createElement("isOverrideValue");
		overrideValue.appendChild(doc.createTextNode("false"));
		property.appendChild(overrideValue);

		Element description = doc.createElement("description");
		description.appendChild(doc.createTextNode(" "));
		property.appendChild(description);
		return doc;
	}

	
	//Return the value of "type" text node based on the Component properties 
	private String getPackageType(ArrayList<String> componentPropertylist,
			int i, Boolean xmlData) {
		String packageTypeTemp = null;

		if (componentPropertylist.get(i).equals(Constants.PROPERTY_PRIORITY)) {
			if (xmlData)
				packageTypeTemp = Constants.XML_STRING;
			else
				packageTypeTemp = Constants.STRING;
		} else if (componentPropertylist.get(i).equals(
				Constants.PROPERTY_FLOWLIMIT)
				|| componentPropertylist.get(i).equals(
						Constants.PROPERTY_PAGETHRESHOLD)) {
			if (xmlData) {
				packageTypeTemp = Constants.XML_INTEGER;
			} else {
				packageTypeTemp = Constants.INTEGER;
			}
		} else {
			if (xmlData) {
				packageTypeTemp = Constants.XML_BOOLEAN;
			} else {
				packageTypeTemp = Constants.BOOLEAN;
			}
		}

		return packageTypeTemp;

	}

	/**
	 * Clean the updated MANIFEST.MF and TIBCO.xml files
	 */
    private void cleanup() {
		for(File file : tempFiles) {
			file.delete();
		}
		getLog().debug("cleaned up the temporary files.");
    }
    /**
     *  Updated the Application manifest just like the module one
     */
    private void updateManifestVersion() {
    	String version = manifest.getMainAttributes().getValue(Constants.BUNDLE_VERSION);
    	String qualifierVersion = VersionParser.getcalculatedOSGiVersion(version);
    	getLog().info("The OSGi verion is " + qualifierVersion + " for Maven version of " + version);
    	manifest.getMainAttributes().putValue(Constants.BUNDLE_VERSION, qualifierVersion);
    }
}
