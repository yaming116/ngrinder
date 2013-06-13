/* 
 * Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License. 
 */
package org.ngrinder.script.handler;

import static org.ngrinder.common.util.CollectionUtils.buildMap;
import static org.ngrinder.common.util.CollectionUtils.newArrayList;
import static org.ngrinder.common.util.CollectionUtils.newHashMap;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.maven.cli.MavenCli;
import org.ngrinder.common.exception.NGrinderRuntimeException;
import org.ngrinder.common.util.PathUtil;
import org.ngrinder.common.util.PropertiesWrapper;
import org.ngrinder.common.util.UrlUtils;
import org.ngrinder.model.User;
import org.ngrinder.script.model.FileCategory;
import org.ngrinder.script.model.FileEntry;
import org.ngrinder.script.model.FileType;
import org.ngrinder.script.repository.FileEntryRepository;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import org.tmatesoft.svn.core.wc.SVNRevision;

/**
 * Groovy Maven project {@link ScriptHandler}.
 * 
 * @author JunHo Yoon
 * @since 3.2
 */
@Component
public class GroovyMavenProjectScriptHandler extends GroovyScriptHandler implements ProjectHandler {

	/**
	 * Constructor.
	 */
	public GroovyMavenProjectScriptHandler() {
		super("groovy_maven", "", "Groovy Maven Project", "groovy");
	}

	private static final String RESOURCES = "/src/main/resources/";
	private static final String JAVA = "/src/main/java/";
	private static final String LIB = "/lib/";

	@Override
	public boolean canHandle(FileEntry fileEntry) {
		if (fileEntry.getCreatedUser() == null) {
			return false;
		}
		String path = fileEntry.getPath();
		if (!path.contains(JAVA) || !FilenameUtils.isExtension(path, "groovy")) {
			return false;
		}

		return getFileEntryRepository().hasFileEntry(fileEntry.getCreatedUser(),
						path.substring(0, path.lastIndexOf(JAVA)) + "/pom.xml");
	}

	@Override
	public Integer displayOrder() {
		return 400;
	}

	@Override
	protected Integer order() {
		return 200;
	}

	@Override
	public List<FileEntry> getLibAndResourceEntries(User user, FileEntry scriptEntry, long revision) {
		List<FileEntry> fileList = newArrayList();
		String basePath = getBasePath(scriptEntry);
		FileEntryRepository fileEntryRepository = getFileEntryRepository();
		for (FileEntry eachFileEntry : fileEntryRepository.findAll(user, basePath + RESOURCES, revision, true)) {
			FileType fileType = eachFileEntry.getFileType();
			if (fileType.isResourceDistributable()) {
				fileList.add(eachFileEntry);
			}
		}

		for (FileEntry eachFileEntry : fileEntryRepository.findAll(user, basePath + JAVA, revision, true)) {
			FileType fileType = eachFileEntry.getFileType();

			if (fileType.isLibDistribtable() && !eachFileEntry.getPath().equals(scriptEntry.getPath())) {
				fileList.add(eachFileEntry);
			}
		}

		for (FileEntry eachFileEntry : fileEntryRepository.findAll(user, basePath + LIB, revision)) {
			FileType fileType = eachFileEntry.getFileType();
			if (fileType.isLibDistribtable()) {
				fileList.add(eachFileEntry);
			}
		}
		fileList.add(fileEntryRepository.findOne(user, basePath + "/pom.xml", SVNRevision.create(revision)));
		return fileList;
	}

	@Override
	protected String calcDistSubPath(String basePath, FileEntry each) {
		String calcDistSubPath = super.calcDistSubPath(basePath, each);
		if (calcDistSubPath.startsWith(JAVA)) {
			return calcDistSubPath.substring(JAVA.length() - 1);
		} else if (calcDistSubPath.startsWith(RESOURCES)) {
			return calcDistSubPath.substring(RESOURCES.length() - 1);
		}
		return calcDistSubPath;
	}

	@Override
	protected void prepareDistMore(Long testId, User user, FileEntry script, File distDir,
					PropertiesWrapper properties, ProcessingResultPrintStream processingResult) {
		String pomPathInSVN = PathUtil.join(getBasePath(script), "pom.xml");
		MavenCli cli = new MavenCli();
		processingResult.println("\nCopy dependencies by running 'mvn dependency:copy-dependencies"
						+ " -DoutputDirectory=./lib -DexcludeScope=provided'");

		int result = cli.doMain(new String[] { // goal specification
						"dependency:copy-dependencies", // run dependency goal
								"-DoutputDirectory=./lib", // to the lib folder
								"-DexcludeScope=provided" // but exclude the provided library
						}, distDir.getAbsolutePath(), processingResult, processingResult);
		boolean success = (result == 0);
		if (success) {
			processingResult.printf("\nDependencies in %s was copied.\n", pomPathInSVN);
			LOGGER.info("Dependencies in {} is copied into {}/lib folder", pomPathInSVN, distDir.getAbsolutePath());
		} else {
			processingResult.printf("\nDependencies copy in %s is failed.\n", pomPathInSVN);
			LOGGER.info("Dependencies copy in {} is failed.", pomPathInSVN);
		}
		// Then it's not necessary to include pom.xml anymore.
		FileUtils.deleteQuietly(new File(distDir, "pom.xml"));
		processingResult.setSuccess(result == 0);
	}

	@Override
	public boolean prepareScriptEnv(User user, String path, String fileName, String name, // LF
					String url, boolean createLib) {
		path = PathUtil.join(path, fileName);
		try {
			// Create Dir entry
			createBaseDirectory(user, path);
			// Create each template entries
			createFileEntries(user, path, name, url);
			if (createLib) {
				createLibraryDirectory(user, path);
			}
		} catch (IOException e) {
			throw new NGrinderRuntimeException("Error while patching script_template", e);
		}
		return false;
	}

	private void createLibraryDirectory(User user, String path) {
		FileEntry fileEntry = new FileEntry();
		fileEntry.setPath(path + "/lib");
		fileEntry.setFileType(FileType.DIR);
		fileEntry.setDescription("put private libraries here");
		getFileEntryRepository().save(user, fileEntry, null);
	}

	private void createFileEntries(User user, String path, String name, String url) throws IOException {
		File scriptTemplateDir;
		scriptTemplateDir = new ClassPathResource("/script_template/" + getKey()).getFile();
		for (File each : FileUtils.listFiles(scriptTemplateDir, null, true)) {
			try {
				String subpath = each.getPath().substring(scriptTemplateDir.getPath().length());
				String fileContent = FileUtils.readFileToString(each, "UTF8");
				fileContent = fileContent.replace("${userName}", user.getUserName());
				fileContent = fileContent.replace("${name}", name);
				fileContent = fileContent.replace("${url}", url);
				FileEntry fileEntry = new FileEntry();
				fileEntry.setContent(fileContent);
				fileEntry.setPath(FilenameUtils.normalize(PathUtil.join(path, subpath), true));
				fileEntry.setDescription("create groovy maven project");
				String hostName = UrlUtils.getHost(url);
				if (StringUtils.isNotEmpty(hostName)
								&& fileEntry.getFileType().getFileCategory() == FileCategory.SCRIPT) {
					Map<String, String> properties = newHashMap();
					properties.put("targetHosts", UrlUtils.getHost(url));
					fileEntry.setProperties(properties);
				}
				getFileEntryRepository().save(user, fileEntry, "UTF8");
			} catch (IOException e) {
				throw new NGrinderRuntimeException("Error while saving " + each.getName(), e);
			}
		}
	}

	private void createBaseDirectory(User user, String path) {
		FileEntry dirEntry = new FileEntry();
		dirEntry.setPath(path);
		// Make it eclipse default folder ignored.
		dirEntry.setProperties(buildMap("svn:ignore", ".project\n.classpath\n.settings\ntarget"));
		dirEntry.setFileType(FileType.DIR);
		dirEntry.setDescription("create groovy maven project");
		getFileEntryRepository().save(user, dirEntry, null);
	}

	@Override
	protected String getBasePath(FileEntry script) {
		String path = script.getPath();
		return path.substring(0, path.lastIndexOf(JAVA));
	}

	@Override
	public FileEntry getDefaultQuickTestFilePath(String path) {
		FileEntry fileEntry = new FileEntry();
		fileEntry.setPath(path + "/src/main/java/TestRunner.groovy");
		return fileEntry;
	}

}