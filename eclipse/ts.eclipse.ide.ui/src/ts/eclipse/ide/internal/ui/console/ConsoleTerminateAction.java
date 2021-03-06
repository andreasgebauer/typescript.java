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
package ts.eclipse.ide.internal.ui.console;

import org.eclipse.jface.action.Action;
import org.eclipse.osgi.util.NLS;

import ts.client.ITypeScriptClientListener;
import ts.client.ITypeScriptServiceClient;
import ts.eclipse.ide.core.resources.IIDETypeScriptProject;
import ts.eclipse.ide.internal.ui.TypeScriptUIMessages;
import ts.eclipse.ide.ui.TypeScriptUIImageResource;

/**
 * Stop ts Server action.
 * 
 */
public class ConsoleTerminateAction extends Action implements ITypeScriptClientListener {

	private final IIDETypeScriptProject project;

	public ConsoleTerminateAction(IIDETypeScriptProject project) {
		this.project = project;
		setToolTipText(
				NLS.bind(TypeScriptUIMessages.ConsoleTerminateAction_tooltipText, project.getProject().getName()));
		setImageDescriptor(TypeScriptUIImageResource.getImageDescriptor(TypeScriptUIImageResource.IMG_STOP_ENABLED));
		setDisabledImageDescriptor(TypeScriptUIImageResource.getImageDescriptor(TypeScriptUIImageResource.IMG_STOP_DISABLED));
		setHoverImageDescriptor(TypeScriptUIImageResource.getImageDescriptor(TypeScriptUIImageResource.IMG_STOP_ENABLED));
		project.addServerListener(this);
	}

	@Override
	public void run() {
		try {
			if (project.isServerDisposed()) {
				this.setEnabled(false);
			} else {
				project.disposeServer();
			}
		} catch (Throwable e) {
		}
	}

	public void dispose() {
		project.removeServerListener(this);
	}

	@Override
	public void onStart(ITypeScriptServiceClient server) {
		this.setEnabled(true);
	}

	@Override
	public void onStop(ITypeScriptServiceClient server) {
		this.setEnabled(false);
	}

}
