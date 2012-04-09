/*******************************************************************************
 * Copyright (c) 2006, 2012 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *     Wind River Systems - Fix for viewer state save/restore [188704] 
 *     Pawel Piech (Wind River) - added support for a virtual tree model viewer (Bug 242489)
 *     Dorin Ciuca - Top index fix (Bug 324100)
 *******************************************************************************/
package org.eclipse.debug.internal.ui.viewers.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import org.eclipse.core.runtime.Assert;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.ISafeRunnable;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.ListenerList;
import org.eclipse.core.runtime.SafeRunner;
import org.eclipse.core.runtime.Status;
import org.eclipse.core.runtime.jobs.Job;
import org.eclipse.debug.core.IRequest;
import org.eclipse.debug.internal.ui.DebugUIPlugin;
import org.eclipse.debug.internal.ui.viewers.model.provisional.ICheckboxModelProxy;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IChildrenUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IElementContentProvider;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelChangedListener;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelProxy;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelProxy2;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelProxyFactory;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IModelProxyFactory2;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IPresentationContext;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IStateUpdateListener;
import org.eclipse.debug.internal.ui.viewers.model.provisional.ITreeModelViewer;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdate;
import org.eclipse.debug.internal.ui.viewers.model.provisional.IViewerUpdateListener;
import org.eclipse.debug.internal.ui.viewers.model.provisional.TreeModelViewerFilter;
import org.eclipse.jface.viewers.IContentProvider;
import org.eclipse.jface.viewers.IStructuredSelection;
import org.eclipse.jface.viewers.TreePath;
import org.eclipse.jface.viewers.TreeSelection;
import org.eclipse.jface.viewers.Viewer;
import org.eclipse.jface.viewers.ViewerFilter;
import org.eclipse.swt.widgets.Display;

/**
 * Content provider for a virtual tree.
 * 
 * @since 3.3
 */
public class TreeModelContentProvider implements ITreeModelContentProvider, IContentProvider, IModelChangedListener {

    /**
     * Tree model viewer that this content provider is used with.
     */
    private IInternalTreeModelViewer fViewer;

    /**
     * Mask used to filter delta updates coming from the model.
     */
    private int fModelDeltaMask = ~0;

    /**
     * Map tree paths to model proxy responsible for element
     * 
     * Used to install different model proxy instances for one element depending
     * on the tree path.
     */
    private Map fTreeModelProxies = new HashMap(); // tree model proxy by
                                                   // element tree path

    /**
     * Map element to model proxy responsible for it.
     * 
     * Used to install a single model proxy which is responsible for all
     * instances of an element in the model tree.
     */
    private Map fModelProxies = new HashMap(); // model proxy by element

    /**
     * Map of nodes that have been filtered from the viewer.
     */
    private FilterTransform fTransform = new FilterTransform();

    /**
     * Model listeners
     */
    private ListenerList fModelListeners = new ListenerList();

    /**
     * Viewer update listeners
     */
    private ListenerList fUpdateListeners = new ListenerList();

    /**
     * Flag indicating whether we are currently in a model sequence.
     * @see IViewerUpdateListener
     */
    private boolean fModelSequenceRunning = false;
    
    /**
     * Map of updates in progress: element path -> list of requests
     */
    private Map fRequestsInProgress = new HashMap();

    /**
     * Map of dependent requests waiting for parent requests to complete:
     * element path -> list of requests
     */
    private Map fWaitingRequests = new HashMap();

    private List fCompletedUpdates = new ArrayList();
    
    private Runnable fCompletedUpdatesJob;

    private ViewerStateTracker fStateTracker = new ViewerStateTracker(this);
    
    /**
     * Update type constants
     */
    static final int UPDATE_SEQUENCE_BEGINS = 0;

    static final int UPDATE_SEQUENCE_COMPLETE = 1;

    static final int UPDATE_BEGINS = 2;

    static final int UPDATE_COMPLETE = 3;


    /**
     * Constant for an empty tree path.
     */
    static final TreePath EMPTY_TREE_PATH = new TreePath(new Object[] {});

    public void dispose() {
        if (fViewer == null) return;
        
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
        
        // cancel pending updates
        Iterator iterator = fRequestsInProgress.values().iterator();
        while (iterator.hasNext()) {
            List requests = (List) iterator.next();
            Iterator reqIter = requests.iterator();
            while (reqIter.hasNext()) {
                ((IRequest) reqIter.next()).cancel();
            }
        }
        fWaitingRequests.clear();

        fStateTracker.dispose();
        fModelListeners.clear();
        fUpdateListeners.clear();
        disposeAllModelProxies();
        
        synchronized(this) {
            fViewer = null;
        }
    }

    /**
     * @return Returns whether the content provider is disposed.
     */
    boolean isDisposed() {
        synchronized(this) {
            return fViewer == null;
        }
    }

    public void inputChanged(Viewer viewer, Object oldInput, Object newInput) {
        synchronized(this) {
            fViewer = (IInternalTreeModelViewer) viewer;
        }
        
        Assert.isTrue( fViewer.getDisplay().getThread() == Thread.currentThread() );

        if (oldInput != null) {
            fStateTracker.saveViewerState(oldInput);
        }
    }
    
    public void postInputChanged(IInternalTreeModelViewer viewer, Object oldInput, Object newInput) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
        
        cancelSubtreeUpdates(TreePath.EMPTY);
        disposeAllModelProxies();
        cancelSubtreeUpdates(TreePath.EMPTY);
        fTransform.clear();
        if (newInput != null) {
            installModelProxy(newInput, TreePath.EMPTY);
            fStateTracker.restoreViewerState(newInput);
        } 
    }

    public void addViewerUpdateListener(IViewerUpdateListener listener) {
        fUpdateListeners.add(listener);
    }

    public void removeViewerUpdateListener(IViewerUpdateListener listener) {
        fUpdateListeners.remove(listener);
    }

    public void addStateUpdateListener(IStateUpdateListener listener) {
        fStateTracker.addStateUpdateListener(listener);
    }

    public void preserveState(TreePath path) {
        fStateTracker.appendToPendingStateDelta(path);        
    }
    
    public void removeStateUpdateListener(IStateUpdateListener listener) {
        fStateTracker.removeStateUpdateListener(listener);
    }

    public void addModelChangedListener(IModelChangedListener listener) {
        fModelListeners.add(listener);
    }

    public void removeModelChangedListener(IModelChangedListener listener) {
        fModelListeners.remove(listener);
    }
    
    public void cancelRestore(final TreePath path, final int flags) {
        fStateTracker.cancelRestore(path, flags);
    }

    public boolean setChecked(TreePath path, boolean checked) {
        IModelProxy elementProxy = getElementProxy(path);
        if (elementProxy instanceof ICheckboxModelProxy) {
            return ((ICheckboxModelProxy) elementProxy).setChecked(getPresentationContext(), getViewer().getInput(), path, checked);
        }                               
        return false;
    }
    
    /**
     * Installs the model proxy for the given element into this content provider
     * if not already installed.
     * @param input the input to install the model proxy on
     * @param path the {@link TreePath} to install the proxy for
     */
    private void installModelProxy(Object input, TreePath path) {
        
        if (!fTreeModelProxies.containsKey(path) && !fModelProxies.containsKey(path.getLastSegment())) {
            Object element = path.getSegmentCount() != 0 ? path.getLastSegment() : input;
            IModelProxy proxy = null;
            IModelProxyFactory2 modelProxyFactory2 = ViewerAdapterService.getModelProxyFactory2(element);
            if (modelProxyFactory2 != null) {
                proxy = modelProxyFactory2.createTreeModelProxy(input, path, getPresentationContext());
                if (proxy != null) {
                    fTreeModelProxies.put(path, proxy);
                }
            }
            if (proxy == null) {
                IModelProxyFactory modelProxyFactory = ViewerAdapterService.getModelProxyFactory(element);
                if (modelProxyFactory != null) {
                    proxy = modelProxyFactory.createModelProxy(element, getPresentationContext());
                    if (proxy != null) {
                        fModelProxies.put(element, proxy);
                    }
                }
            }

            if (proxy instanceof IModelProxy2) {
                proxy.addModelChangedListener(this);
                ((IModelProxy2)proxy).initialize(getViewer());
            } else if (proxy != null) {
                final IModelProxy finalProxy = proxy;
                Job job = new Job("Model Proxy installed notification job") {//$NON-NLS-1$
                    protected IStatus run(IProgressMonitor monitor) {
                        if (!monitor.isCanceled()) {
                            IPresentationContext context = null;
                            Viewer viewer = null;
                            synchronized (TreeModelContentProvider.this) {
                                if (!isDisposed()) {
                                    context = getPresentationContext();
                                    viewer = (Viewer) getViewer();
                                }
                            }
                            if (viewer != null && context != null && !finalProxy.isDisposed()) {
                                finalProxy.init(context);
                                finalProxy.addModelChangedListener(TreeModelContentProvider.this);
                                finalProxy.installed(viewer);
                            }
                        }
                        return Status.OK_STATUS;
                    }

                    public boolean shouldRun() {
                        return !isDisposed();
                    }
                };
                job.setSystem(true);
                job.schedule();
            }
        }
    }

    /**
     * Finds the model proxy that an element with a given path is associated with.
     * @param path Path of the element.
     * @return Element's model proxy.
     */
    private IModelProxy getElementProxy(TreePath path) {
        while (path != null) {
            IModelProxy proxy = (IModelProxy) fTreeModelProxies.get(path);
            if (proxy != null) {
                return proxy;
            }

            Object element = path.getSegmentCount() == 0 ? getViewer().getInput() : path.getLastSegment();
            proxy = (IModelProxy) fModelProxies.get(element);
            if (proxy != null) {
                return proxy;
            }

            path = path.getParentPath();
        }
        return null;
    }

    /**
     * Removes all model proxies
     */
    private void disposeAllModelProxies() {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
        
        Iterator updatePolicies = fModelProxies.values().iterator();
        while (updatePolicies.hasNext()) {
            IModelProxy proxy = (IModelProxy) updatePolicies.next();
            proxy.dispose();
        }
        fModelProxies.clear();

        updatePolicies = fTreeModelProxies.values().iterator();
        while (updatePolicies.hasNext()) {
            IModelProxy proxy = (IModelProxy) updatePolicies.next();
            proxy.dispose();
        }
        fTreeModelProxies.clear();
    }
    
    /**
     * Removes the model proxy installed for the given element, if any.
     * @param path the {@link TreePath} to dispose the model proxy for
     */
    private void disposeModelProxy(TreePath path) {
        IModelProxy proxy = (IModelProxy) fTreeModelProxies.remove(path);
        if (proxy != null) {
            proxy.dispose();
        }
        proxy = (IModelProxy) fModelProxies.remove(path.getLastSegment());
        if (proxy != null) {
            proxy.dispose();
        }
    }    
    
    public void modelChanged(final IModelDelta delta, final IModelProxy proxy) {
        Display display = null;

        // Check if the viewer is still available, i.e. if the content provider
        // is not disposed.
        synchronized(this) {
            if (fViewer != null && !proxy.isDisposed()) {
                display = fViewer.getDisplay();
            }
        }
        if (display != null) {
            // If we're in display thread, process the delta immediately to 
            // avoid "skid" in processing events.
            if (Thread.currentThread().equals(display.getThread())) {
                doModelChanged(delta, proxy);
            }
            else {
                fViewer.getDisplay().asyncExec(new Runnable() {
                    public void run() {
                        doModelChanged(delta, proxy);
                    }
                });
            }
        }
    }

    /**
     * Executes the model proxy in UI thread.
     * @param delta Delta to process
     * @param proxy Proxy that fired the delta.
     */
    private void doModelChanged(IModelDelta delta, IModelProxy proxy) {
        if (!proxy.isDisposed()) {
            if (DebugUIPlugin.DEBUG_DELTAS && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
                DebugUIPlugin.trace("RECEIVED DELTA: " + delta.toString()); //$NON-NLS-1$
            }

            updateModel(delta, getModelDeltaMask());

            // Initiate model update sequence before notifying of the model changed. 
            trigger(null);
            
            // Call model listeners after updating the viewer model.
            Object[] listeners = fModelListeners.getListeners();
            for (int i = 0; i < listeners.length; i++) {
                ((IModelChangedListener) listeners[i]).modelChanged(delta, proxy);
            }
        }
    }
    
    public void setModelDeltaMask(int mask) {
        fModelDeltaMask = mask;
    }

    public int getModelDeltaMask() {
        return fModelDeltaMask;
    }

    public void updateModel(IModelDelta delta, int mask) {
        IModelDelta[] deltaArray = new IModelDelta[] { delta };
        updateNodes(deltaArray, mask & (IModelDelta.REMOVED | IModelDelta.UNINSTALL));
        updateNodes(deltaArray, mask & ITreeModelContentProvider.UPDATE_MODEL_DELTA_FLAGS
            & ~(IModelDelta.REMOVED | IModelDelta.UNINSTALL));
        updateNodes(deltaArray, mask & ITreeModelContentProvider.CONTROL_MODEL_DELTA_FLAGS);
        
        fStateTracker.checkIfRestoreComplete();
    }

    /**
     * Returns a tree path for the node including the root element.
     * 
     * @param node
     *            model delta
     * @return corresponding tree path
     */
    TreePath getFullTreePath(IModelDelta node) {
        ArrayList list = new ArrayList();
        while (node.getParentDelta() != null) {
            list.add(0, node.getElement());
            node = node.getParentDelta();
        }
        return new TreePath(list.toArray());
    }

    /**
     * Returns a tree path for the node, *not* including the root element.
     * 
     * @param node
     *            model delta
     * @return corresponding tree path
     */
    TreePath getViewerTreePath(IModelDelta node) {
        ArrayList list = new ArrayList();
        IModelDelta parentDelta = node.getParentDelta();
        while (parentDelta != null) {
            list.add(0, node.getElement());
            node = parentDelta;
            parentDelta = node.getParentDelta();
        }
        return new TreePath(list.toArray());
    }

    /**
     * Returns the viewer this content provider is working for.
     * 
     * @return viewer
     */
    protected IInternalTreeModelViewer getViewer() {
        synchronized(this) {
            return fViewer;
        }
    }

    public int viewToModelIndex(TreePath parentPath, int index) {
        return fTransform.viewToModelIndex(parentPath, index);
    }

    public int viewToModelCount(TreePath parentPath, int count) {
        return fTransform.viewToModelCount(parentPath, count);
    }

    public int modelToViewIndex(TreePath parentPath, int index) {
        return fTransform.modelToViewIndex(parentPath, index);
    }

    public int modelToViewChildCount(TreePath parentPath, int count) {
        return fTransform.modelToViewCount(parentPath, count);
    }

    public boolean areTreeModelViewerFiltersApplicable(Object parentElement) {
        ViewerFilter[] filters = fViewer.getFilters();
        if (filters.length > 0) {
            for (int j = 0; j < filters.length; j++) {
                if (filters[j] instanceof TreeModelViewerFilter &&
                    ((TreeModelViewerFilter)filters[j]).isApplicable(fViewer, parentElement)) 
                {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean shouldFilter(Object parentElementOrTreePath, Object element) {
        ViewerFilter[] filters = fViewer.getFilters();
        if (filters.length > 0) {
            for (int j = 0; j < filters.length; j++) {
                if (filters[j] instanceof TreeModelViewerFilter) {
                    // Skip the filter if not applicable to parent element
                    Object parentElement = parentElementOrTreePath instanceof TreePath 
                        ? ((TreePath)parentElementOrTreePath).getLastSegment() : parentElementOrTreePath;
                    if (parentElement == null) parentElement = fViewer.getInput();
                    if (!((TreeModelViewerFilter)filters[j]).isApplicable(fViewer, parentElement)) {
                        continue;
                    }
                }
                
                if (!(filters[j].select((Viewer) fViewer, parentElementOrTreePath, element))) {
                    return true;
                }
            }
        }
        return false;
    }

    public void unmapPath(TreePath path) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
        fTransform.clear(path);
        cancelSubtreeUpdates(path);
    }

    
    boolean addFilteredIndex(TreePath parentPath, int index, Object element) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
        return fTransform.addFilteredIndex(parentPath, index, element);
    }

    void removeElementFromFilters(TreePath parentPath, int index) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
        fTransform.removeElementFromFilters(parentPath, index);
    }

    boolean removeElementFromFilters(TreePath parentPath, Object element) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
        return fTransform.removeElementFromFilters(parentPath, element);
    }

    void setModelChildCount(TreePath parentPath, int childCount) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
        fTransform.setModelChildCount(parentPath, childCount);
    }

    boolean isFiltered(TreePath parentPath, int index) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
        return fTransform.isFiltered(parentPath, index);
    }

    int[] getFilteredChildren(TreePath parent) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
        return fTransform.getFilteredChildren(parent);
    }

    void clearFilteredChild(TreePath parent, int modelIndex) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
        fTransform.clear(parent, modelIndex);
    }

    void clearFilters(TreePath parent) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
        fTransform.clear(parent);
    }

    /**
     * Notification an update request has started
     * 
     * @param update the update to notify about
     */
    void updateStarted(ViewerUpdateMonitor update) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
        
        List requests = (List) fRequestsInProgress.get(update.getSchedulingPath());
        if (requests == null) {
            requests = new ArrayList();
            fRequestsInProgress.put(update.getSchedulingPath(), requests);
        }
        requests.add(update);
        if (!fModelSequenceRunning) {
        	fModelSequenceRunning = true;
            if (DebugUIPlugin.DEBUG_UPDATE_SEQUENCE && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
                DebugUIPlugin.trace("MODEL SEQUENCE BEGINS"); //$NON-NLS-1$
            }
            notifyUpdate(UPDATE_SEQUENCE_BEGINS, null);
        }
        if (DebugUIPlugin.DEBUG_UPDATE_SEQUENCE && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
            DebugUIPlugin.trace("\tBEGIN - " + update); //$NON-NLS-1$
        }
        notifyUpdate(UPDATE_BEGINS, update);
    }

    /**
     * Notification an update request has completed
     * 
     * @param updates the updates to notify
     */
    void updatesComplete(final List updates) {
    	for (int i = 0; i < updates.size(); i++) {
    		ViewerUpdateMonitor update = (ViewerUpdateMonitor)updates.get(i);
	        notifyUpdate(UPDATE_COMPLETE, update);
	        if (DebugUIPlugin.DEBUG_UPDATE_SEQUENCE && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
	            DebugUIPlugin.trace("\tEND - " + update); //$NON-NLS-1$
	        }
    	}
    	
    	// Wait a single cycle to allow viewer to queue requests triggered by completed updates.
        getViewer().getDisplay().asyncExec(new Runnable() {
            public void run() {
                if (isDisposed()) return;

            	for (int i = 0; i < updates.size(); i++) {
            		ViewerUpdateMonitor update = (ViewerUpdateMonitor)updates.get(i);
	                
            		// Search for update in list using identity test.  Otherwise a completed canceled
            		// update may trigger removal of up-to-date running update on the same element.
	                List requests = (List) fRequestsInProgress.get(update.getSchedulingPath());
	            	boolean found = false;
	            	if (requests != null) {
    	            	for (int j = 0; j < requests.size(); j++) {
    	            		if (requests.get(j) == update) {
    	            			found = true;
    	            			requests.remove(j);
    	            			break;
    	            		}
    	            	}
	            	}
	            	
	            	if (found) {
	                    // Trigger may initiate new updates, so wait to remove requests array from 
	                    // fRequestsInProgress map.  This way updateStarted() will not send a 
	                    // redundant "UPDATE SEQUENCE STARTED" notification.
	                    trigger(update.getSchedulingPath());
	            	} else {
	            		// Update may be removed from in progress list if it was canceled by schedule().
	                    Assert.isTrue( update.isCanceled() );
	            	}
                    if (requests != null && requests.isEmpty()) {
                        fRequestsInProgress.remove(update.getSchedulingPath());
                    }
            	}
                if (fRequestsInProgress.isEmpty() && fWaitingRequests.isEmpty() && fModelSequenceRunning) {
                	fModelSequenceRunning = false;
                    if (DebugUIPlugin.DEBUG_UPDATE_SEQUENCE && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
                        DebugUIPlugin.trace("MODEL SEQUENCE ENDS"); //$NON-NLS-1$
                    }
                    notifyUpdate(UPDATE_SEQUENCE_COMPLETE, null);
                }            	
            }
        });
            
    }
    
    /**
     * @return Returns true if there are outstanding updates in the viewer.
     */
    boolean areRequestsPending() {
        return !fRequestsInProgress.isEmpty() || !fWaitingRequests.isEmpty();
    }

    /**
     * @return Returns the state tracker for the content provider.
     */
    ViewerStateTracker getStateTracker() {
        return fStateTracker;
    }
    
    /**
     * Notifies listeners about given update.
     * @param type Type of update to call listeners with.
     * @param update Update to notify about.
     */
    private void notifyUpdate(final int type, final IViewerUpdate update) {
        if (!fUpdateListeners.isEmpty()) {
            Object[] listeners = fUpdateListeners.getListeners();
            for (int i = 0; i < listeners.length; i++) {
                final IViewerUpdateListener listener = (IViewerUpdateListener) listeners[i];
                SafeRunner.run(new ISafeRunnable() {
                    public void run() throws Exception {
                        switch (type) {
                        case UPDATE_SEQUENCE_BEGINS:
                            listener.viewerUpdatesBegin();
                            break;
                        case UPDATE_SEQUENCE_COMPLETE:
                            listener.viewerUpdatesComplete();
                            break;
                        case UPDATE_BEGINS:
                            listener.updateStarted(update);
                            break;
                        case UPDATE_COMPLETE:
                            listener.updateComplete(update);
                            break;
                        }
                    }

                    public void handleException(Throwable exception) {
                        DebugUIPlugin.log(exception);
                    }
                });
            }
        }
    }

    /**
     * Cancels outstanding updates for the element at given path and its 
     * children.
     * @param path Path of element.
     */
    private void cancelSubtreeUpdates(TreePath path) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );        
        
        Iterator iterator = fRequestsInProgress.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = (Entry) iterator.next();
            TreePath entryPath = (TreePath) entry.getKey();
            if (entryPath.startsWith(path, null)) {
                List requests = (List) entry.getValue();
                Iterator reqIter = requests.iterator();
                while (reqIter.hasNext()) {
                    // Cancel update and remove from requests list.  Removing from 
                    // fRequestsInProgress ensures that isRequestBlocked() won't be triggered 
                    // by a canceled update.
                    ((IRequest) reqIter.next()).cancel();
                    reqIter.remove();
                }
            }
        }
        List purge = new ArrayList();
        iterator = fWaitingRequests.keySet().iterator();
        while (iterator.hasNext()) {
            TreePath entryPath = (TreePath) iterator.next();
            if (entryPath.startsWith(path, null)) {
                purge.add(entryPath);
            }
        }
        iterator = purge.iterator();
        while (iterator.hasNext()) {
            fWaitingRequests.remove(iterator.next());
        }
        
        fStateTracker.cancelStateSubtreeUpdates(path);
    }

    /**
     * Returns whether this given request should be run, or should wait for
     * parent update to complete.
     * 
     * @param update the update the schedule
     */
    private void schedule(final ViewerUpdateMonitor update) {
    	Assert.isTrue(getViewer().getDisplay().getThread() == Thread.currentThread());
    	
        TreePath schedulingPath = update.getSchedulingPath();
        List requests = (List) fWaitingRequests.get(schedulingPath);
        if (requests == null) {
            requests = new LinkedList();
            requests.add(update);
            fWaitingRequests.put(schedulingPath, requests);

            List inProgressList = (List)fRequestsInProgress.get(schedulingPath);
            if (inProgressList != null) {
                int staleUpdateIndex = inProgressList.indexOf(update);
                if (staleUpdateIndex >= 0) {
                    // Cancel update and remove from requests list.  Removing from 
                    // fRequestsInProgress ensures that isRequestBlocked() won't be triggered 
                    // by a canceled update.
                    ViewerUpdateMonitor staleUpdate = (ViewerUpdateMonitor)inProgressList.remove(staleUpdateIndex);
                    staleUpdate.cancel();
                    // Note: Do not reset the inProgressList to null.  This would cause the 
                    // updateStarted() method to think that a new update sequence is 
                    // being started.  Since there are waiting requests for this scheduling 
                    // path, the list will be cleaned up later. 
                }
            }
            if (inProgressList == null || inProgressList.isEmpty()) {
                getViewer().getDisplay().asyncExec(new Runnable() {
                    public void run() {
                    	if (isDisposed()) return;
                        trigger(update.getSchedulingPath());
                    }
                });
            }
        } else {
            // there are waiting requests: coalesce with existing request and add to list
            requests.add(coalesce(requests, update));
        }
    }
    
    /**
     * Tries to coalesce the given request with any request in the list.  If a match is found, 
     * the resulting request is then coalesced again with candidates in list.
     * @param requests List of waiting requests to coalesce with 
     * @param toCoalesce request to coalesce
     * @return Returns either the coalesced request.  If no match was found it returns the 
     * toCoalesce parameter request.  Either way the returned request needs to be added to the 
     * waiting requests list.  
     */
    private ViewerUpdateMonitor coalesce(List requests, ViewerUpdateMonitor toCoalesce) {
        Iterator reqIter = requests.iterator();
        while (reqIter.hasNext()) {
            ViewerUpdateMonitor waiting = (ViewerUpdateMonitor) reqIter.next();
            if (waiting.coalesce(toCoalesce)) {
                requests.remove(waiting);
                // coalesced with existing request, done
                // try to coalesce the combined requests with other waiting requests
                return coalesce(requests, waiting);
            }
        }
        return toCoalesce;
    }

    /**
     * Returns whether there are outstanding ChildrenUpdate updates for the given path.
     * This method is expected to be called during processing of a ChildrenRequest, 
     * therefore one running children request is ignored.
     * @param path Path of element to check.
     * @return True if there are outstanding children updates for given element.
     */
    boolean areChildrenUpdatesPending(TreePath path) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );        
        
        List requests = (List) fWaitingRequests.get(path);
        if (requests != null) {
            for (int i = 0; i < requests.size(); i++) {
                if (requests.get(i) instanceof ChildrenUpdate) {
                    return true;
                }
            }
        }
        requests = (List) fRequestsInProgress.get(path);
        if (requests != null) {
            int numChildrenUpdateRequests = 0;
            for (int i = 0; i < requests.size(); i++) {
                if (requests.get(i) instanceof ChildrenUpdate) {
                    if (++numChildrenUpdateRequests > 1) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Triggers waiting requests based on the given request that just 
     * completed.  
     * <p>
     * Requests are processed in order such that updates to 
     * children are delayed until updates for parent elements are completed.
     * This allows the expansion/selection state of the elements to be 
     * properly restored as new elements are retrieved from model.  
     * </p>
     * @param schedulingPath schedulingPath path or requests to start processing.  May 
     * be <code>null</code> to start the shortest path request.
     */
    private void trigger(TreePath schedulingPath) {
    	Assert.isTrue(getViewer().getDisplay().getThread() == Thread.currentThread());
    	
        if (fWaitingRequests.isEmpty()) {
            return;
        }
        List waiting = (List) fWaitingRequests.get(schedulingPath);
        if (waiting == null) {
            // no waiting, update the entry with the shortest path
            int length = Integer.MAX_VALUE;
            Iterator entries = fWaitingRequests.entrySet().iterator();
            Entry candidate = null;
            while (entries.hasNext()) {
                Entry entry = (Entry) entries.next();
                TreePath key = (TreePath) entry.getKey();
                if (key.getSegmentCount() < length && !isRequestBlocked(key)) {
                    candidate = entry;
                    length = key.getSegmentCount();
                }
            }
            if (candidate != null) {
                startHighestPriorityRequest((TreePath) candidate.getKey(), (List) candidate.getValue());
            }
        } else if (!isRequestBlocked(schedulingPath)) {
            // start the highest priority request
            startHighestPriorityRequest(schedulingPath, waiting);
        }
    }

    /**
     * Returns true if there are running requests for any parent element of 
     * the given tree path. 
     * @param requestPath Path of element to check.
     * @return Returns true if requests are running.
     */
    private boolean isRequestBlocked(TreePath requestPath) {
        TreePath parentPath = requestPath;
        List parentRequests = (List)fRequestsInProgress.get(parentPath); 
        while (parentRequests == null || parentRequests.isEmpty()) {
            parentPath = parentPath.getParentPath();
            if (parentPath == null) {
                // no running requests: start request
                return false;
            }
            parentRequests = (List)fRequestsInProgress.get(parentPath);
        }
        return true;
    }
    
    /**
     * @param key the {@link TreePath}
     * @param waiting the list of waiting requests
     */
    private void startHighestPriorityRequest(TreePath key, List waiting) {
        int priority = 4;
        ViewerUpdateMonitor next = null;
        Iterator requests = waiting.iterator();
        while (requests.hasNext()) {
            ViewerUpdateMonitor vu = (ViewerUpdateMonitor) requests.next();
            if (vu.getPriority() < priority) {
                next = vu;
                priority = next.getPriority();
            }
        }
        if (next != null) {
            waiting.remove(next);
            if (waiting.isEmpty()) {
                fWaitingRequests.remove(key);
            }
            next.start();
        }
    }

    /**
     * Returns the element corresponding to the given tree path.
     * 
     * @param path
     *            tree path
     * @return model element
     */
    protected Object getElement(TreePath path) {
        if (path.getSegmentCount() > 0) {
            return path.getLastSegment();
        }
        return getViewer().getInput();
    }

    /**
     * Reschedule any children updates in progress for the given parent that
     * have a start index greater than the given index. An element has been
     * removed at this index, invalidating updates in progress.
     * 
     * @param parentPath
     *            view tree path to parent element
     * @param modelIndex
     *            index at which an element was removed
     */
    private void rescheduleUpdates(TreePath parentPath, int modelIndex) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );        
        
        List requests = (List) fRequestsInProgress.get(parentPath);
        List reCreate = null;
        if (requests != null) {
            Iterator iterator = requests.iterator();
            while (iterator.hasNext()) {
                IViewerUpdate update = (IViewerUpdate) iterator.next();
                if (update instanceof IChildrenUpdate) {
                    IChildrenUpdate childrenUpdate = (IChildrenUpdate) update;
                    if (childrenUpdate.getOffset() > modelIndex) {
                        // Cancel update and remove from requests list.  Removing from 
                        // fRequestsInProgress ensures that isRequestBlocked() won't be triggered 
                        // by a canceled update.
                        childrenUpdate.cancel();
                        iterator.remove();
                        if (reCreate == null) {
                            reCreate = new ArrayList();
                        }
                        reCreate.add(childrenUpdate);
                        if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
                            DebugUIPlugin.trace("canceled update in progress handling REMOVE: " + childrenUpdate); //$NON-NLS-1$
                        }
                    }
                }
            }
        }
        requests = (List) fWaitingRequests.get(parentPath);
        if (requests != null) {
            Iterator iterator = requests.iterator();
            while (iterator.hasNext()) {
                IViewerUpdate update = (IViewerUpdate) iterator.next();
                if (update instanceof IChildrenUpdate) {
                    IChildrenUpdate childrenUpdate = (IChildrenUpdate) update;
                    if (childrenUpdate.getOffset() > modelIndex) {
                        ((ChildrenUpdate) childrenUpdate).setOffset(childrenUpdate.getOffset() - 1);
                        if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
                            DebugUIPlugin.trace("modified waiting update handling REMOVE: " + childrenUpdate); //$NON-NLS-1$
                        }
                    }
                }
            }
        }
        // re-schedule canceled updates at new position.
        // have to do this last else the requests would be waiting and
        // get modified.
        if (reCreate != null) {
            Iterator iterator = reCreate.iterator();
            while (iterator.hasNext()) {
                IChildrenUpdate childrenUpdate = (IChildrenUpdate) iterator.next();
                int start = childrenUpdate.getOffset() - 1;
                int end = start + childrenUpdate.getLength();
                for (int i = start; i < end; i++) {
                    doUpdateElement(parentPath, i);
                }
            }
        }
    }
    
	private void doUpdateChildCount(TreePath path) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );        
        
		Object element = getElement(path);
		IElementContentProvider contentAdapter = ViewerAdapterService.getContentProvider(element);
		if (contentAdapter != null) {
			ChildrenCountUpdate request = new ChildrenCountUpdate(this, getViewer().getInput(), path, element, contentAdapter);
			schedule(request);
		}
	}	
	
	void doUpdateElement(TreePath parentPath, int modelIndex) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );        
        
		Object parent = getElement(parentPath);
		IElementContentProvider contentAdapter = ViewerAdapterService.getContentProvider(parent);
		if (contentAdapter != null) {
			ChildrenUpdate request = new ChildrenUpdate(this, getViewer().getInput(), parentPath, parent, modelIndex, contentAdapter);
			schedule(request);
		}			
	}	
	
	private void doUpdateHasChildren(TreePath path) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );        
        
		Object element = getElement(path);
		IElementContentProvider contentAdapter = ViewerAdapterService.getContentProvider(element);
		if (contentAdapter != null) {
			HasChildrenUpdate request = new HasChildrenUpdate(this, getViewer().getInput(), path, element, contentAdapter);
			schedule(request);
		}
	}		
	
	/**
	 * Checks if there are outstanding updates that may replace the element 
	 * at given path. 
	 * @param path Path of element to check.
	 * @return Returns true if there are outstanding updates.
	 */
    boolean areElementUpdatesPending(TreePath path) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );        
        
        TreePath parentPath = path.getParentPath();
        List requests = (List) fWaitingRequests.get(path);
        if (requests != null) {
            for (int i = 0; i < requests.size(); i++) {
                ViewerUpdateMonitor update = (ViewerUpdateMonitor) requests.get(i);
                if (update instanceof ChildrenUpdate) {
                    return true;
                }
            }
        }
        requests = (List) fWaitingRequests.get(parentPath);
        if (requests != null) {
            for (int i = 0; i < requests.size(); i++) {
                ViewerUpdateMonitor update = (ViewerUpdateMonitor) requests.get(i);
                if (update.containsUpdate(path)) {
                    return true;
                }
            }
        }
        requests = (List) fRequestsInProgress.get(path);
        if (requests != null) {
            for (int i = 0; i < requests.size(); i++) {
                ViewerUpdateMonitor update = (ViewerUpdateMonitor) requests.get(i);
                if (update instanceof ChildrenUpdate) {
                    return true;
                }
            }
        }
        requests = (List) fRequestsInProgress.get(parentPath);
        if (requests != null) {
            for (int i = 0; i < requests.size(); i++) {
                ViewerUpdateMonitor update = (ViewerUpdateMonitor) requests.get(i);
                if (update.getElement().equals(path.getLastSegment())) {
                    return true;
                }
            }
        }
        return false;
    }
	
    /**
     * Returns the presentation context for this content provider.
     * 
     * @return presentation context
     */
	protected IPresentationContext getPresentationContext() {
	    ITreeModelViewer viewer = getViewer();
	    if (viewer != null) {
	        return viewer.getPresentationContext();
	    } 
	    return null;
	}
	
    /**
     * Updates the viewer with the following deltas.
     * 
     * @param nodes Model deltas to be processed.
     * @param mask the model delta mask
     * @see IModelDelta for a list of masks
     */
    protected void updateNodes(IModelDelta[] nodes, int mask) {
        for (int i = 0; i < nodes.length; i++) {
            IModelDelta node = nodes[i];
            int flags = node.getFlags() & mask;

            if ((flags & IModelDelta.ADDED) != 0) {
                handleAdd(node);
            }
            if ((flags & IModelDelta.REMOVED) != 0) {
                handleRemove(node);
            }
            if ((flags & IModelDelta.CONTENT) != 0) {
                handleContent(node);
            }
            if ((flags & IModelDelta.STATE) != 0) {
                handleState(node);
            }
            if ((flags & IModelDelta.INSERTED) != 0) {
                handleInsert(node);
            }
            if ((flags & IModelDelta.REPLACED) != 0) {
                handleReplace(node);
            }
            if ((flags & IModelDelta.INSTALL) != 0) {
                handleInstall(node);
            }
            if ((flags & IModelDelta.UNINSTALL) != 0) {
                handleUninstall(node);
            }
            if ((flags & IModelDelta.EXPAND) != 0) {
                handleExpand(node);
            }
            if ((flags & IModelDelta.COLLAPSE) != 0) {
                handleCollapse(node);
            }
            if ((flags & IModelDelta.SELECT) != 0) {
                handleSelect(node);
            }
            if ((flags & IModelDelta.REVEAL) != 0) {
                handleReveal(node);
            }
            updateNodes(node.getChildDeltas(), mask);
        }
    }

    protected void handleInstall(IModelDelta delta) {
        installModelProxy(getViewer().getInput(), getFullTreePath(delta));
    }

    protected void handleUninstall(IModelDelta delta) {
        disposeModelProxy(getFullTreePath(delta));
    }	
	
 	protected void handleAdd(IModelDelta delta) {
		IModelDelta parentDelta = delta.getParentDelta();
		TreePath parentPath = getViewerTreePath(parentDelta);
		Object element = delta.getElement();
		int count = parentDelta.getChildCount();
		if (count > 0) {
		    setModelChildCount(parentPath, count);
		    int modelIndex = count - 1;
		    if (delta.getIndex() != -1) {
		    	// assume addition at end, unless index specified by delta
		    	modelIndex = delta.getIndex();
		    }
			if (shouldFilter(parentPath, element)) {
				addFilteredIndex(parentPath, modelIndex, element);
				if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
					DebugUIPlugin.trace("[filtered] handleAdd(" + delta.getElement() + ") > modelIndex: " + modelIndex); //$NON-NLS-1$ //$NON-NLS-2$
				}
				// it was filtered so the child count does not change
			} else {
				if (isFiltered(parentPath, modelIndex)) {
					clearFilteredChild(parentPath, modelIndex);
				}
                int viewIndex = modelToViewIndex(parentPath, modelIndex);
				int viewCount = modelToViewChildCount(parentPath, count);
				if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
					DebugUIPlugin.trace("handleAdd(" + delta.getElement() + ") viewIndex: " + viewIndex + " modelIndex: " + modelIndex + " viewCount: " + viewCount + " modelCount: " + count); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$ //$NON-NLS-5$
				}
				getViewer().setChildCount(parentPath, viewCount);
				getViewer().autoExpand(parentPath);
				getViewer().replace(parentPath, viewIndex, element);
				TreePath childPath = parentPath.createChildPath(element);
				updateHasChildren(childPath);
				fStateTracker.restorePendingStateOnUpdate(childPath, modelIndex, false, false, false);
			}	        
		} else {
			if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
				DebugUIPlugin.trace("handleAdd(" + delta.getElement() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
			}
		    doUpdateChildCount(getViewerTreePath(delta.getParentDelta()));
		}
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.viewers.model.provisional.viewers.ModelContentProvider#handleContent(org.eclipse.debug.internal.ui.viewers.provisional.IModelDelta)
	 */
 	protected void handleContent(IModelDelta delta) {
		if (delta.getParentDelta() == null && delta.getChildCount() == 0) {
			// if the delta is for the root, ensure the root still matches viewer input
			if (!delta.getElement().equals(getViewer().getInput())) {
				return;
			}
		}
		TreePath treePath = getViewerTreePath(delta);
		cancelSubtreeUpdates(treePath);
		getViewer().refresh(getElement(treePath));
	}
	
	/* (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.viewers.model.ModelContentProvider#handleCollapse(org.eclipse.debug.internal.ui.viewers.model.provisional.IModelDelta)
	 */
 	protected void handleCollapse(IModelDelta delta) {
		TreePath elementPath = getViewerTreePath(delta);
		getViewer().setExpandedState(elementPath, false);
        cancelRestore(elementPath, IModelDelta.EXPAND);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.debug.internal.ui.viewers.model.provisional.viewers.ModelContentProvider#handleExpand(org.eclipse.debug.internal.ui.viewers.provisional.IModelDelta)
	 */
 	protected void handleExpand(IModelDelta delta) {
		// expand each parent, then this node
		IModelDelta parentDelta = delta.getParentDelta();
		if (parentDelta != null) {
			if ((parentDelta.getFlags() & IModelDelta.EXPAND) == 0) {
				handleExpand(parentDelta);
			}
			expand(delta);
		} else {
	        int childCount = delta.getChildCount();
	        TreePath elementPath = getViewerTreePath(delta);
	        if (childCount > 0) {
	            int viewCount = modelToViewChildCount(elementPath, childCount);
	            if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
	                DebugUIPlugin.trace("[expand] setChildCount(" + delta.getElement() + ", (model) " + childCount + " (view) " + viewCount); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
	            }
	            getViewer().setChildCount(elementPath, viewCount);	            
	        }
		}
	}
	
 	/**
 	 * Expands the element pointed to by given delta.
 	 * @param delta Delta that points to the element to expand.
 	 */
	private void expand(IModelDelta delta) {
		int childCount = delta.getChildCount();
		int modelIndex = delta.getIndex();
		IInternalTreeModelViewer treeViewer = getViewer();
		TreePath elementPath = getViewerTreePath(delta);
		if (modelIndex >= 0) {
			TreePath parentPath = elementPath.getParentPath();
			if (parentPath == null) {
				parentPath = TreePath.EMPTY;
			}
			int viewIndex = modelToViewIndex(parentPath, modelIndex);
			if (viewIndex >= 0) {
				if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
					DebugUIPlugin.trace("[expand] replace(" + delta.getParentDelta().getElement() + ", (model) " + modelIndex + " (view) " + viewIndex + ", " + delta.getElement()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				}
				treeViewer.replace(parentPath, viewIndex, delta.getElement());
			} else {
				// Element is filtered - if no longer filtered, insert the element
				viewIndex = unfilterElement(parentPath, delta.getElement(), modelIndex);
				if (viewIndex < 0) {
					// insert did not complete
					return;
				}
			}
		}
		if (childCount > 0) {
			int viewCount = modelToViewChildCount(elementPath, childCount);
			if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
				DebugUIPlugin.trace("[expand] setChildCount(" + delta.getElement() + ", (model) " + childCount + " (view) " + viewCount); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			treeViewer.setChildCount(elementPath, viewCount);
	        if (!treeViewer.getExpandedState(elementPath)) {
	            treeViewer.expandToLevel(elementPath, 1);
	            cancelRestore(elementPath, IModelDelta.COLLAPSE);
	        }
		}
	}
	
	/**
	 * Inserts the given child element of the specified parent into the tree if the element
	 * should *no* longer be filtered. Returns the view index of the newly inserted element
	 * or -1 if not inserted.
	 * 
	 * @param parentPath viewer tree path to parent element
	 * @param element element to insert
	 * @param modelIndex index of the element in the model
	 * @return Returns the view index of the newly inserted element
     * or -1 if not inserted.
	 */
	private int unfilterElement(TreePath parentPath, Object element, int modelIndex) {
		// Element is filtered - if no longer filtered, insert the element
		if (shouldFilter(parentPath, element)) {
			if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
				DebugUIPlugin.trace("[unfilter] abort unfilter element: " + element + ", (model) " + modelIndex);  //$NON-NLS-1$ //$NON-NLS-2$
			}
			// still filtered, stop
			return -1;
		}
		// clear the filter an insert the element
		clearFilteredChild(parentPath, modelIndex);
		int viewIndex = modelToViewIndex(parentPath, modelIndex);
		if (viewIndex >= 0) {
			if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
				DebugUIPlugin.trace("[unfilter] insert(" + parentPath.getLastSegment() + ", (model) " + modelIndex + " (view) " + viewIndex + ", " + element); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			}
			getViewer().insert(parentPath, element, viewIndex);
			return viewIndex;
		} else {
			// still filtered - should not happen
			return -1;
		}
	}

	protected void handleInsert(IModelDelta delta) {
		// TODO: filters
		getViewer().insert(getViewerTreePath(delta.getParentDelta()), delta.getElement(), delta.getIndex());
	}

	protected void handleRemove(IModelDelta delta) {
		if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
			DebugUIPlugin.trace("handleRemove(" + delta.getElement() + ")"); //$NON-NLS-1$ //$NON-NLS-2$
		}
		IModelDelta parentDelta = delta.getParentDelta();
		IInternalTreeModelViewer treeViewer = getViewer();
		TreePath parentPath = getViewerTreePath(parentDelta);
		Object element = delta.getElement();
		if (removeElementFromFilters(parentPath, element)) {
			// element was filtered - done
			return;
		}
		int viewIndex = -1;
		int modelIndex = delta.getIndex();
		int unmappedIndex = -1;
		int itemCount = -1;
		if (modelIndex < 0) {
		    itemCount = treeViewer.getChildCount(parentPath);
		    if (itemCount == -1) {
		        clearFilters(parentPath);
		    }
		    viewIndex = treeViewer.findElementIndex(parentPath, element);
		    if (viewIndex >= 0) {
		        modelIndex = viewToModelIndex(parentPath, viewIndex);
		    } else {
		        unmappedIndex = treeViewer.findElementIndex(parentPath, null);
		    }
		} else {
			viewIndex = modelToViewIndex(parentPath, modelIndex);
		}
		if (modelIndex >= 0) {
			// found the element
			if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
				DebugUIPlugin.trace(" - (found) remove(" + parentPath.getLastSegment() + ", viewIndex: " + viewIndex + " modelIndex: " + modelIndex); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			rescheduleUpdates(parentPath, modelIndex);
			getViewer().remove(parentPath, viewIndex);
			removeElementFromFilters(parentPath, modelIndex);
			return;
		}
		if (unmappedIndex >= 0) {
			// did not find the element, but found an unmapped item.
			// remove the unmapped item in it's place and update filters
			if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
				DebugUIPlugin.trace(" - (not found) remove(" + parentPath.getLastSegment() + ", viewIndex: " + viewIndex + " modelIndex: " + modelIndex + " unmapped index: " + unmappedIndex); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
			}
			modelIndex = viewToModelIndex(parentPath, unmappedIndex);
			rescheduleUpdates(parentPath, modelIndex);
			getViewer().remove(parentPath, unmappedIndex);
			removeElementFromFilters(parentPath, modelIndex);
			return;
		}
		int modelCount = parentDelta.getChildCount();
		if (itemCount >= 0 && modelCount >= 0) {
			if (modelToViewChildCount(parentPath, modelCount) == itemCount) {
				// item count matches the parent's child count, don't do anything
				return;
			}
		}
		// failing that, refresh the parent to properly update for non-visible/unmapped children
		// and update filtered indexes
		if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
			DebugUIPlugin.trace(" - (not found) remove/refresh(" + delta.getElement()); //$NON-NLS-1$
		}
		getViewer().remove(getViewerTreePath(delta));
		clearFilters(parentPath);
		getViewer().refresh(parentDelta.getElement());
	}

	protected void handleReplace(IModelDelta delta) {
		TreePath parentPath = getViewerTreePath(delta.getParentDelta());
		int index = delta.getIndex();
		if (index < 0) {
		    index = fTransform.indexOfFilteredElement(parentPath, delta.getElement());
		}
		if (index >= 0) {
		    boolean filtered = isFiltered(parentPath, index);
		    boolean shouldFilter = shouldFilter(parentPath, delta.getReplacementElement());
		    
		    // Update the filter transform
		    if (filtered) {
                clearFilteredChild(parentPath, index);
		    }
		    if (shouldFilter) {
		        addFilteredIndex(parentPath, index, delta.getElement());
		    }
		    
		    // Update the viewer
		    if (filtered) {
		        if (!shouldFilter) {
		            getViewer().insert(parentPath, delta.getReplacementElement(), modelToViewIndex(parentPath, index));
		        } 
		        //else do nothing
		    } else {
		        if (shouldFilter) {
		            getViewer().remove(parentPath, modelToViewIndex(parentPath, index));
		        } else {
		            getViewer().replace(parentPath, delta.getIndex(), delta.getReplacementElement());
		        }
		    }
		}
	}

	protected void handleSelect(IModelDelta delta) {
		int modelIndex = delta.getIndex();
		IInternalTreeModelViewer treeViewer = getViewer();
		// check if selection is allowed
		IStructuredSelection candidate = new TreeSelection(getViewerTreePath(delta));
		if ((delta.getFlags() & IModelDelta.FORCE) == 0 && 
		    !treeViewer.overrideSelection(treeViewer.getSelection(), candidate)) 
		{
			return;
		}
		// empty the selection before replacing elements to avoid materializing elements (@see bug 305739)
		treeViewer.clearSelectionQuiet();
		if (modelIndex >= 0) {
			IModelDelta parentDelta = delta.getParentDelta();
			TreePath parentPath = getViewerTreePath(parentDelta);
			int viewIndex = modelToViewIndex(parentPath, modelIndex);
			if (viewIndex >= 0) {
				// when viewIndex < 0, the element has been filtered - so we should not try to replace
				int modelCount = parentDelta.getChildCount();
				if (modelCount > 0) {
					int viewCount = modelToViewChildCount(parentPath, modelCount);
					if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
						DebugUIPlugin.trace("[select] setChildCount(" + parentDelta.getElement() + ", (model) " + parentDelta.getChildCount() + " (view) " + viewCount ); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
					}
					treeViewer.setChildCount(parentPath, viewCount);
				}
				if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
					DebugUIPlugin.trace("[select] replace(" + parentDelta.getElement() + ", (model) " + modelIndex + " (view) " + viewIndex + ", " + delta.getElement()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				}
				treeViewer.replace(parentPath, viewIndex, delta.getElement());
			}
		}
		TreePath selectionPath = getViewerTreePath(delta);
		if (treeViewer.trySelection(new TreeSelection(selectionPath), false, (delta.getFlags() | IModelDelta.FORCE) == 0)) {
	        cancelRestore(selectionPath, IModelDelta.SELECT);
		}
	}

	protected void handleState(IModelDelta delta) {
		getViewer().update(delta.getElement());
	}

	protected void handleReveal(IModelDelta delta) {
		IModelDelta parentDelta = delta.getParentDelta();
		if (parentDelta != null) {
			handleExpand(parentDelta);
			reveal(delta);
            cancelRestore(getViewerTreePath(delta), IModelDelta.REVEAL);
		}
	}
	
	/**
	 * Reveals the element pointed to by given delta.
	 * @param delta Delta pointing to the element to reveal.
	 */
	private void reveal(IModelDelta delta) {
		int modelIndex = delta.getIndex();
		IInternalTreeModelViewer treeViewer = getViewer();
		TreePath elementPath = getViewerTreePath(delta);
		if (modelIndex >= 0) {
			TreePath parentPath = elementPath.getParentPath();
			if (parentPath == null) {
				parentPath = TreePath.EMPTY;
			}
			int viewIndex = modelToViewIndex(parentPath, modelIndex);
			if (viewIndex >= 0) {
				if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
					DebugUIPlugin.trace("[reveal] replace(" + delta.getParentDelta().getElement() + ", (model) " + modelIndex + " (view) " + viewIndex + ", " + delta.getElement()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$ //$NON-NLS-4$
				}
				treeViewer.replace(parentPath, viewIndex, delta.getElement());
			} else {
				// Element is filtered - insert if filter state changed
				viewIndex = unfilterElement(parentPath, delta.getElement(), modelIndex);
				if (viewIndex < 0) {
					// insert did not complete
					return;
				}
			}

			// only move tree based on force flag and selection policy
			if ((delta.getFlags() & IModelDelta.FORCE) != 0 ||
			    treeViewer.overrideSelection(treeViewer.getSelection(), new TreeSelection(elementPath))) 
			{
			    treeViewer.reveal(parentPath, viewIndex);
			}
		}
	}	
	

	/* (non-Javadoc)
	 * @see org.eclipse.jface.viewers.ILazyTreePathContentProvider#getParents(java.lang.Object)
	 */
	public TreePath[] getParents(Object element) {
		return null;
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jface.viewers.ILazyTreePathContentProvider#updateChildCount(org.eclipse.jface.viewers.TreePath, int)
	 */
	public void updateChildCount(TreePath treePath, int currentChildCount) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );        
        
		if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
			DebugUIPlugin.trace("updateChildCount(" + getElement(treePath) + ", " + currentChildCount + ")"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		doUpdateChildCount(treePath);
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jface.viewers.ILazyTreePathContentProvider#updateElement(org.eclipse.jface.viewers.TreePath, int)
	 */
	public void updateElement(TreePath parentPath, int viewIndex) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
	    
		int modelIndex = viewToModelIndex(parentPath, viewIndex);
		if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
			DebugUIPlugin.trace("updateElement("+ getElement(parentPath) + ", " + viewIndex + ") > modelIndex = " + modelIndex); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
		}
		doUpdateElement(parentPath, modelIndex);		
	}

	/* (non-Javadoc)
	 * @see org.eclipse.jface.viewers.ILazyTreePathContentProvider#updateHasChildren(org.eclipse.jface.viewers.TreePath)
	 */
	public void updateHasChildren(TreePath path) {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
	    
		if (DebugUIPlugin.DEBUG_CONTENT_PROVIDER && DebugUIPlugin.DEBUG_TEST_PRESENTATION_ID(getPresentationContext())) {
			DebugUIPlugin.trace("updateHasChildren(" + getElement(path)); //$NON-NLS-1$
		}
		doUpdateHasChildren(path);
	}

	/**
	 * Schedules given update to be performed on the viewer.
	 * Updates are queued up if they are completed in the same 
	 * UI cycle.
	 * @param update Update to perform.
	 */
	void scheduleViewerUpdate(ViewerUpdateMonitor update) {
	    Display display;
	    synchronized(this) {
	        if (isDisposed()) return;
	        display = getViewer().getDisplay();
	        fCompletedUpdates.add(update);
            if (fCompletedUpdatesJob == null) {
	            fCompletedUpdatesJob = new Runnable() {
	                public void run() {
	                    if (!isDisposed()) {
	                        performUpdates();
	                    }
	                }
	            };
	            display.asyncExec(fCompletedUpdatesJob);
	        }
	    }
	}

	/**
	 * Perform the updates pointed to by given array on the viewer.
	 */
	private void performUpdates() {
        Assert.isTrue( getViewer().getDisplay().getThread() == Thread.currentThread() );
	    
        List jobCompletedUpdates;
        synchronized(TreeModelContentProvider.this) {
            if (isDisposed()) {
                return;
            }
            jobCompletedUpdates = fCompletedUpdates;
            fCompletedUpdatesJob = null;
            fCompletedUpdates = new ArrayList();
        }
        // necessary to check if viewer is disposed
        try {
	        for (int i = 0; i < jobCompletedUpdates.size(); i++) {
	        	ViewerUpdateMonitor completedUpdate = (ViewerUpdateMonitor)jobCompletedUpdates.get(i);
                if (!completedUpdate.isCanceled() && !isDisposed()) {
                    IStatus status = completedUpdate.getStatus();
                    if (status == null || status.isOK()) {
                        completedUpdate.performUpdate();
                    }
                } 
	        }
        } finally {
            updatesComplete(jobCompletedUpdates);
        }        
	}
}
