/*******************************************************************************
 * Copyright (c) 2000, 2003 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.compare.examples.structurecreator;

import org.eclipse.swt.graphics.*;

import org.eclipse.jface.text.*;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IStatus;
import org.eclipse.core.runtime.Status;

import org.eclipse.compare.*;
import org.eclipse.compare.structuremergeviewer.*;


/**
 * This structure creator parses input containing key/value pairs
 * and returns the pairs as a tree of <code>IStructureComparator</code>s.
 * Each key/value pair must be on a separate line and keys are separated
 * from values by a '='.
 * <p>
 * Example:
 * <pre>
 *    foo=bar
 *    name=joe
 * </pre>
 */
public class KeyValuePairStructureCreator implements IStructureCreator {
	
	static class KeyValueNode extends DocumentRangeNode implements ITypedElement {
		
		String fValue;
		
		public KeyValueNode(String id, String value, IDocument doc, int start, int length) {
			super(0, id, doc, start, length);
			fValue= value;
		}
		
		String getValue() {
			return fValue;
		}
				
		/**
		 * @see ITypedElement#getName
		 */
		public String getName() {
			return this.getId();
		}

		/**
		 * Every key/value pair is of type "kvtxt". We register a TextMergeViewer for it. 
		 * @see ITypedElement#getType
		 */
		public String getType() {
			return "kvtxt"; //$NON-NLS-1$
		}
		
		/**
		 * @see ITypedElement#getImage
		 */
		public Image getImage() {
			return CompareUI.getImage(getType());
		}
	}
		
		
	public KeyValuePairStructureCreator() {
	}
	
	/**
	 * This title will be shown in the title bar of the structure compare pane.
	 */
	public String getName() {
		return Util.getString("KeyValuePairStructureCreator.title"); //$NON-NLS-1$
	}

	/**
	 * Returns a node.
	 */
	public IStructureComparator getStructure(Object input) {
		
		if (!(input instanceof IStreamContentAccessor))
			return null;
		
		IStreamContentAccessor sca= (IStreamContentAccessor) input;
		try {
			String contents= Util.readString(sca);
			if (contents == null)
				contents= ""; //$NON-NLS-1$
			Document doc= new Document(contents);
							
			KeyValueNode root= new KeyValueNode("root", "", doc, 0, doc.getLength()); //$NON-NLS-1$ //$NON-NLS-2$
		
			for (int i= 0; i < doc.getNumberOfLines(); i++) {
				
				IRegion r= doc.getLineInformation(i);
				String s= doc.get(r.getOffset(), r.getLength());
				int start= r.getOffset();
					
				String key= ""; //$NON-NLS-1$
				String value= ""; //$NON-NLS-1$
				int pos= s.indexOf('=');
				if (pos >= 0) {
					key= s.substring(0, pos);
					value= s.substring(pos+1);
				} else {
					key= s;
				}
				if (key.length() > 0)
					root.addChild(new KeyValueNode(key, value, doc, start, s.length()));
			}
			return root;
		} catch (CoreException ex) {
			String message= Util.getString("KeyValuePairStructureCreator.CoreException.message"); //$NON-NLS-1$
			CompareUI.getPlugin().getLog().log(new Status(IStatus.ERROR, CompareUI.PLUGIN_ID, 0, message, ex));
		} catch (BadLocationException ex) {
			String message= Util.getString("KeyValuePairStructureCreator.BadLocationException.message"); //$NON-NLS-1$
			CompareUI.getPlugin().getLog().log(new Status(IStatus.ERROR, CompareUI.PLUGIN_ID, 0, message, ex));
		}
				
		return null;
	}
	
	public void save(IStructureComparator structure, Object input) {
		if (input instanceof IEditableContent && structure instanceof KeyValueNode) {
			IDocument doc= ((KeyValueNode)structure).getDocument();
			IEditableContent bca= (IEditableContent) input;
			String c= doc.get();
			bca.setContent(c.getBytes());
		}
	}
	
	public String getContents(Object node, boolean ignoreWhitespace) {
		if (node instanceof KeyValueNode) {
			String s= ((KeyValueNode)node).getValue();
			if (ignoreWhitespace)
				s= s.trim();
			return s;
		}
		return null;
	}
	
	public IStructureComparator locate(Object path, Object source) {
		return null;
	}
}
