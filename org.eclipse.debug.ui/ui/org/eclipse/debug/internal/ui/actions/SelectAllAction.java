package org.eclipse.debug.internal.ui.actions;

/*
 * (c) Copyright IBM Corp. 2002.
 * All Rights Reserved.
 */

import org.eclipse.debug.ui.IDebugView;
import org.eclipse.jface.action.IAction;
import org.eclipse.jface.viewers.ISelection;
import org.eclipse.jface.viewers.TreeViewer;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.ui.IViewPart;

public abstract class SelectAllAction extends AbstractListenerActionDelegate {

	/**
	 * @see AbstractDebugActionDelegate#isEnabledFor(Object)
	 */
	protected boolean isEnabledFor(Object element) {
		return true;
	}

	/**
	 * @see IViewActionDelegate#init(IViewPart)
	 */
	public void init(IViewPart view) {
		setView(view);
		setWindow(view.getViewSite().getWorkbenchWindow());
		getPage().addPartListener(this);
		getPage().getWorkbenchWindow().addPageListener(this);
	}

	/**
	 * @see AbstractDebugActionDelegate#initialize(IAction, ISelection)
	 */
	protected boolean initialize(IAction action, ISelection selection) {
		if (!isInitialized()) {
			IDebugView debugView =
				(IDebugView) getView().getAdapter(IDebugView.class);
			if (debugView != null) {
				debugView.setAction(getActionId(), action);
			}
			return super.initialize(action, selection);
		}
		return false;
	}

	protected abstract String getActionId();
	
	/**
	 * @see IActionDelegate#run(IAction)
	 */
	public void run(IAction action){
		doAction(null);
	}

	/**
	 * @see AbstractDebugActionDelegate#doAction(Object)
	 */
	protected void doAction(Object element) {
		if (!(getView() instanceof IDebugView)) {
			return;
		}
		Viewer viewer = ((IDebugView) getView()).getViewer();
		if (!(viewer instanceof TreeViewer)) {
			return;
		}
		((TreeViewer) viewer).getTree().selectAll();
		//ensure that the selection change callback is fired
		viewer.setSelection(viewer.getSelection());
	}
	
	protected abstract void update();
	
	protected void update(IAction action, ISelection selection) {
		update();
	}
}