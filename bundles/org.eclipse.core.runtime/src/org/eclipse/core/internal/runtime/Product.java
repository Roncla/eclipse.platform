/**********************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
 * All rights reserved.   This program and the accompanying materials
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors: 
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.core.internal.runtime;

import java.util.Dictionary;
import org.eclipse.core.runtime.IConfigurationElement;
import org.eclipse.core.runtime.IProduct;

public class Product implements IProduct {
	String application = null;
	String name = null;
	String id = null;
	String description = null;
	Dictionary properties;
	
	public Product(IConfigurationElement element) {
		if (element == null)
			return;
		application = element.getAttribute("application");
		name = element.getAttribute("name");
		id = element.getAttribute("id");
		description = element.getAttribute("description");
		loadProperties(element);
	}
	
	private void loadProperties(IConfigurationElement element) {
	}
	
	public String getApplication() {
		return application;
	}

	public String getName() {
		return name;
	}

	public String getDescription() {
		return description;
	}

	public String getId() {
		return id;
	}
	public String getProperty(String key) {
		return (String)properties.get(key);
	}
}
