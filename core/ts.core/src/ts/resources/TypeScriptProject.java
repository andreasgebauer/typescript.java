/**
 *  Copyright (c) 2015-2016 Angelo ZERR.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *  Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 *  Lorenzo Dalla Vecchia <lorenzo.dallavecchia@webratio.com> - openExternalProject
 */
package ts.resources;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import ts.TypeScriptException;
import ts.client.CommandNames;
import ts.client.ITypeScriptClientListener;
import ts.client.ITypeScriptServiceClient;
import ts.client.TypeScriptServiceClient;
import ts.client.completions.ICompletionEntryMatcher;
import ts.client.completions.ICompletionEntryMatcherProvider;
import ts.client.diagnostics.DiagnosticEvent;
import ts.client.projectinfo.ProjectInfo;
import ts.cmd.tsc.CompilerOptionCapability;
import ts.cmd.tsc.ITypeScriptCompiler;
import ts.cmd.tsc.TypeScriptCompiler;
import ts.cmd.tslint.ITypeScriptLint;
import ts.cmd.tslint.TypeScriptLint;
import ts.utils.FileUtils;

/**
 * TypeScript project implementation.
 *
 */
public class TypeScriptProject implements ITypeScriptProject, ICompletionEntryMatcherProvider {

	private final File projectDir;
	private ITypeScriptProjectSettings projectSettings;

	// TypeScript service client
	private ITypeScriptServiceClient client;
	private final Map<String, ITypeScriptFile> openedFiles;

	// TypeScript compiler
	private ITypeScriptCompiler compiler;

	private final Map<String, Object> data;
	private final List<ITypeScriptClientListener> listeners;
	protected final Object serverLock = new Object();
	private ITypeScriptLint tslint;

	private final Map<CommandNames, Boolean> serverCapabilities;
	private Map<CompilerOptionCapability, Boolean> compilerCapabilities;

	private List<String> supportedCodeFixes;

	private ProjectInfo projectInfo;

	public TypeScriptProject(File projectDir, ITypeScriptProjectSettings projectSettings) {
		this.projectDir = projectDir;
		this.projectSettings = projectSettings;
		this.openedFiles = new HashMap<String, ITypeScriptFile>();
		this.data = new HashMap<String, Object>();
		this.listeners = new ArrayList<ITypeScriptClientListener>();
		this.serverCapabilities = new HashMap<CommandNames, Boolean>();
		this.compilerCapabilities = new HashMap<CompilerOptionCapability, Boolean>();
		this.projectInfo = null;
	}

	protected void setProjectSettings(ITypeScriptProjectSettings projectSettings) {
		this.projectSettings = projectSettings;
	}

	/**
	 * Returns the project base directory.
	 * 
	 * @return the project base directory.
	 */
	public File getProjectDir() {
		return projectDir;
	}

	/**
	 * Gets the paths of the tsconfig.json files to take into account for this
	 * project. This is called each time a new server is started for this
	 * project.
	 * <p>
	 * The default implementation returns an empty list.
	 * 
	 * @return tsconfigFilePaths list of tsconfig.json paths, relative to the
	 *         project directory.
	 */
	protected List<String> getTsconfigFilePaths() {
		return Collections.emptyList();
	}

	void openFile(ITypeScriptFile tsFile) throws TypeScriptException {
		String name = tsFile.getName();
		String contents = tsFile.getContents();
		getClient().openFile(name, contents);
		this.openedFiles.put(name, tsFile);
	}

	void closeFile(ITypeScriptFile tsFile) throws TypeScriptException {
		String name = tsFile.getName();
		getClient().closeFile(name);
		((AbstractTypeScriptFile) tsFile).setOpened(false);
		this.openedFiles.remove(name);
	}

	@Override
	public List<String> getSupportedCodeFixes() throws TypeScriptException {
		if (supportedCodeFixes != null) {
			return supportedCodeFixes;
		}
		if (canSupport(CommandNames.GetSupportedCodeFixes)) {
			try {
				supportedCodeFixes = getClient().getSupportedCodeFixes().get(5000, TimeUnit.MILLISECONDS);
			} catch (Exception e) {
				e.printStackTrace();
			}
		} else {
			supportedCodeFixes = new ArrayList<String>();
		}
		return supportedCodeFixes;
	}

	@Override
	public boolean canFix(Integer errorCode) {
		try {
			return getSupportedCodeFixes().contains(String.valueOf(errorCode));
		} catch (Throwable e) {
			e.printStackTrace();
			return false;
		}
	}

	@Override
	public final ITypeScriptServiceClient getClient() throws TypeScriptException {
		synchronized (serverLock) {
			if (isServerDisposed()) {
				try {
					this.client = createServiceClient(getProjectDir());
					copyListeners();
					onCreateClient(client);
					// determine root files and project name
					String projectName = projectDir.getCanonicalPath();
					List<String> rootFiles = new ArrayList<>();
					for (String tsconfigFilePath : getTsconfigFilePaths()) {
						rootFiles.add(FileUtils.getPath(new File(projectDir, tsconfigFilePath)));
					}
					// opens or updates the external project
					client.openExternalProject(projectName, rootFiles);
				} catch (Exception e) {
					if (e instanceof TypeScriptException) {
						throw (TypeScriptException) e;
					}
					throw new TypeScriptException(e);
				}
			}
			return client;
		}
	}

	protected void onCreateClient(ITypeScriptServiceClient client) {

	}

	@Override
	public synchronized ITypeScriptFile getOpenedFile(String fileName) {
		return openedFiles.get(fileName);
	}

	@Override
	public void dispose() throws TypeScriptException {
		disposeServer();
		getProjectSettings().dispose();
	}

	/**
	 * Create service client which consumes tsserver.
	 * 
	 * @param projectDir
	 * @return
	 * @throws TypeScriptException
	 */
	protected ITypeScriptServiceClient createServiceClient(File projectDir) throws TypeScriptException {
		File nodeFile = getProjectSettings().getNodejsInstallPath();
		File typescriptDir = getProjectSettings().getTypesScriptDir();
		TypeScriptServiceClient client = new TypeScriptServiceClient(getProjectDir(), typescriptDir, nodeFile,
				getProjectSettings().isEnableTelemetry(), getProjectSettings().isDisableAutomaticTypingAcquisition(), getProjectSettings().getTsserverPluginsFile());
		client.setCompletionEntryMatcherProvider(this);
		return client;
	}

	/**
	 * Create compiler which consumes tsc.
	 * 
	 * @return
	 * @throws TypeScriptException
	 */
	protected ITypeScriptCompiler createCompiler() throws TypeScriptException {
		File nodeFile = getProjectSettings().getNodejsInstallPath();
		File tscFile = getProjectSettings().getTscFile();
		return createCompiler(tscFile, nodeFile);
	}

	protected ITypeScriptCompiler createCompiler(File tscFile, File nodejsFile) {
		return new TypeScriptCompiler(tscFile, nodejsFile);
	}

	// ----------------------- TypeScript server listeners.

	@Override
	public void addServerListener(ITypeScriptClientListener listener) {
		synchronized (listeners) {
			if (!listeners.contains(listener)) {
				listeners.add(listener);
			}
		}
		copyListeners();
	}

	@Override
	public void removeServerListener(ITypeScriptClientListener listener) {
		synchronized (listeners) {
			listeners.remove(listener);
		}
		synchronized (serverLock) {
			if (hasClient()) {
				this.client.removeClientListener(listener);
			}
		}
	}

	protected boolean hasClient() {
		return client != null;
	}

	private void copyListeners() {
		synchronized (serverLock) {
			if (hasClient()) {
				for (ITypeScriptClientListener listener : listeners) {
					client.addClientListener(listener);
				}
			}
		}
	}

	@Override
	public void disposeServer() {
		synchronized (serverLock) {
			if (!isServerDisposed()) {
				if (hasClient()) {
					// close opened files
					List<ITypeScriptFile> files = new ArrayList<ITypeScriptFile>(openedFiles.values());
					for (ITypeScriptFile openedFile : files) {
						try {
							openedFile.close();
						} catch (TypeScriptException e) {
							e.printStackTrace();
						}
					}
					client.dispose();
					client = null;
				}
			}
		}
		serverCapabilities.clear();
		supportedCodeFixes = null;
	}

	@Override
	public void disposeCompiler() {
		if (compiler != null) {
			compiler.dispose();
			compiler = null;
			compilerCapabilities.clear();
		}
	}

	@SuppressWarnings("unchecked")
	public <T> T getData(String key) {
		synchronized (data) {
			return (T) data.get(key);
		}
	}

	public void setData(String key, Object value) {
		synchronized (data) {
			data.put(key, value);
		}
	}

	@Override
	public boolean isServerDisposed() {
		synchronized (serverLock) {
			return client == null || client.isDisposed();
		}
	}

	@Override
	public ITypeScriptCompiler getCompiler() throws TypeScriptException {
		if (compiler == null) {
			compiler = createCompiler();
		}
		return compiler;
	}

	@Override
	public ITypeScriptLint getTslint() throws TypeScriptException {
		if (tslint == null) {
			tslint = createTslint();
		}
		return tslint;
	}

	@Override
	public void disposeTslint() {
		if (tslint != null) {
			// tslint.dispose();
			tslint = null;
		}
	}

	protected ITypeScriptLint createTslint() throws TypeScriptException {
		File nodeFile = getProjectSettings().getNodejsInstallPath();
		File tslintFile = getProjectSettings().getTslintFile();
		File tslintJsonFile = getProjectSettings().getCustomTslintJsonFile();
		return createTslint(tslintFile, tslintJsonFile, nodeFile);
	}

	protected ITypeScriptLint createTslint(File tslintFile, File tslintJsonFile, File nodejsFile) {
		return new TypeScriptLint(tslintFile, tslintJsonFile, nodejsFile);
	}

	@Override
	public ITypeScriptProjectSettings getProjectSettings() {
		return projectSettings;
	}

	@Override
	public boolean canSupport(CommandNames command) {
		Boolean support = serverCapabilities.get(command);
		if (support == null) {
			support = command.canSupport(getProjectSettings().getTypeScriptVersion());
			serverCapabilities.put(command, support);
		}
		return support;
	}

	@Override
	public boolean canSupport(CompilerOptionCapability option) {
		Boolean support = compilerCapabilities.get(option);
		if (support == null) {
			support = option.canSupport(getProjectSettings().getTypeScriptVersion());
			compilerCapabilities.put(option, support);
		}
		return support;
	}

	@Override
	public ICompletionEntryMatcher getMatcher() {
		return getProjectSettings().getCompletionEntryMatcher();
	}

	@Override
	public CompletableFuture<List<DiagnosticEvent>> geterrForProject(String file, int delay)
			throws TypeScriptException {
		/*
		 * if (projectInfo == null) {
		 * CompletableFuture.allOf(getClient().projectInfo(file, null, true),
		 * geterrForProjectRequest(file, delay)); CompletableFuture<ProjectInfo>
		 * projectInfoFuture = getClient().projectInfo(file, null, true); return
		 * projectInfoFuture. thenAccept(projectInfo -> return
		 * geterrForProjectRequest(file, delay)); } else {
		 */

		try {
			ProjectInfo projectInfo = getClient().projectInfo(file, null, true).get(5000, TimeUnit.MILLISECONDS);
			return getClient().geterrForProject(file, delay, projectInfo);
		} catch (Exception e) {
			if (e instanceof TypeScriptException) {
				throw (TypeScriptException) e;
			}
			throw new TypeScriptException(e);
		}

	}
}
