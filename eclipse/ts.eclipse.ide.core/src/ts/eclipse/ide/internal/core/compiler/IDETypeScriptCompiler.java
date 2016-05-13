/**
 *  Copyright (c) 2015-2016 Angelo ZERR.
 *  All rights reserved. This program and the accompanying materials
 *  are made available under the terms of the Eclipse Public License v1.0
 *  which accompanies this distribution, and is available at
 *  http://www.eclipse.org/legal/epl-v10.html
 *
 *  Contributors:
 *  Angelo Zerr <angelo.zerr@gmail.com> - initial API and implementation
 */
package ts.eclipse.ide.internal.core.compiler;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IMarker;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.osgi.util.NLS;

import ts.TypeScriptException;
import ts.compiler.CompilerOptions;
import ts.compiler.TypeScriptCompiler;
import ts.eclipse.ide.core.compiler.IIDETypeScriptCompiler;
import ts.eclipse.ide.core.resources.jsconfig.IDETsconfigJson;
import ts.eclipse.ide.core.utils.TypeScriptResourceUtil;
import ts.eclipse.ide.internal.core.TypeScriptCoreMessages;

/**
 * Extends {@link TypeScriptCompiler} to use Eclipse {@link IResource}.
 */
public class IDETypeScriptCompiler extends TypeScriptCompiler implements IIDETypeScriptCompiler {

	public IDETypeScriptCompiler(File tscFile, File nodejsFile) {
		super(tscFile, nodejsFile);
	}

	@Override
	public void compile(IDETsconfigJson tsconfig, List<IFile> tsFiles) throws TypeScriptException, CoreException {
		IFile tsconfigFile = tsconfig.getTsconfigFile();
		if (tsconfig.isBuildOnSave()) {
			// Compile the whole files for the given tsconfig.json
			compile(tsconfigFile, tsconfig.getCompilerOptions(), tsFiles, true);
		} else {
			if (tsconfig.isCompileOnSave()) {
				// compileOnSave is activated
				if (tsconfig.hasOutFile()) {
					// tsconfig.json defines "compilerOptions/outFile" or
					// "compilerOptions/out", the ts files cannot be compiled
					// add a warning by suggesting to use "buildOnSave"
					for (IFile tsFile : tsFiles) {
						// delete existing marker
						TypeScriptResourceUtil.deleteTscMarker(tsFile);
						// add warning marker
						TypeScriptResourceUtil.addTscMarker(tsFile,
								NLS.bind(TypeScriptCoreMessages.tsconfig_cannot_use_compileOnSave_with_outFile_error,
										tsconfig.getTsconfigFile().getProjectRelativePath().toString()),
								IMarker.SEVERITY_WARNING, 1);
						// delete emitted files *.js, *.js.map
						TypeScriptResourceUtil.deleteEmittedFiles(tsFile, tsconfig);
					}
				} else {
					// check that ts files are in the scope of the tsconfig.json
					List<IFile> tsFilesToCompile = new ArrayList<IFile>(tsFiles);
					for (IFile tsFile : tsFiles) {
						if (!tsconfig.isInScope(tsFile)) {
							tsFilesToCompile.remove(tsFile);
							addCompilationContextMarkerError(tsFile, tsconfig.getTsconfigFile());
						}
					}
					// compile the list of ts files.
					compile(tsconfigFile, tsconfig.getCompilerOptions(), tsFilesToCompile, false);
				}
			} else {
				// compileOnSave is setted to false in the
				// tsconfig.json,
				// add a warning marker inside each ts files that user
				// whish
				// to compile
				for (IFile tsFile : tsFiles) {
					// delete existing marker
					TypeScriptResourceUtil.deleteTscMarker(tsFile);
					// add warning marker
					TypeScriptResourceUtil.addTscMarker(tsFile,
							NLS.bind(TypeScriptCoreMessages.tsconfig_compileOnSave_disable_error,
									tsconfig.getTsconfigFile().getProjectRelativePath().toString()),
							IMarker.SEVERITY_WARNING, 1);
					// delete emitted files *.js, *.js.map
					TypeScriptResourceUtil.deleteEmittedFiles(tsFile, tsconfig);
				}
			}
		}
	}

	private void compile(IFile tsConfigFile, CompilerOptions tsconfigOptions, List<IFile> tsFiles, boolean buildOnSave)
			throws TypeScriptException, CoreException {
		IContainer container = tsConfigFile.getParent();
		IDETypeScriptCompilerReporter reporter = new IDETypeScriptCompilerReporter(container,
				!buildOnSave ? tsFiles : null);
		CompilerOptions options = createOptions(tsconfigOptions, buildOnSave);
		// compile ts files to *.js, *.js.map files
		super.compile(container.getLocation().toFile(), options, reporter.getFileNames(), reporter);
		// refresh *.js, *.js.map which have been generated with tsc.
		reporter.refreshEmittedFiles();
		// check the given list of ts files are the same than tsc
		// --listFiles
		for (IFile tsFile : tsFiles) {
			if (!reporter.getFilesToRefresh().contains(tsFile)) {
				addCompilationContextMarkerError(tsFile, tsConfigFile);
			}
		}

	}

	private void addCompilationContextMarkerError(IFile tsFile, IFile tsConfigFile) throws CoreException {
		// The ts file to compile is not in the compilation context of
		// the tsconfig.json
		// delete existing marker
		TypeScriptResourceUtil.deleteTscMarker(tsFile);
		// add warning marker
		TypeScriptResourceUtil.addTscMarker(tsFile, NLS.bind(TypeScriptCoreMessages.tsconfig_compilation_context_error,
				tsConfigFile.getProjectRelativePath().toString()), IMarker.SEVERITY_WARNING, 1);
	}

	private CompilerOptions createOptions(CompilerOptions tsconfigOptions, boolean buildOnSave) {
		CompilerOptions options = tsconfigOptions != null ? new CompilerOptions(tsconfigOptions)
				: new CompilerOptions();
		if (buildOnSave && tsconfigOptions != null) {
			// buildOnSave, copy outFile
			options.setOutFile(tsconfigOptions.getOutFile());
		}
		options.setListFiles(true);
		options.setWatch(false);
		return options;
	}

}
