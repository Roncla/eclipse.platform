/*******************************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.ui.internal.intro.impl.model;

import org.osgi.framework.*;
import org.w3c.dom.*;

/**
 * An intro image element.
 */
public class IntroImage extends AbstractBaseIntroElement {

    protected static final String TAG_IMAGE = "img"; //$NON-NLS-1$

    private static final String ATT_SRC = "src"; //$NON-NLS-1$
    private static final String ATT_ALT = "alt"; //$NON-NLS-1$

    private String src;
    private String alt;

    IntroImage(Element element, Bundle bundle) {
        super(element, bundle);
        src = getAttribute(element, ATT_SRC);
        alt = getAttribute(element, ATT_ALT);

        // Resolve.
        src = IntroModelRoot.getPluginLocation(src, bundle);
    }

    /**
     * @return Returns the alt.
     */
    public String getAlt() {
        return alt;
    }

    /**
     * @return Returns the src.
     */
    public String getSrc() {
        return src;
    }

    /*
     * (non-Javadoc)
     * 
     * @see org.eclipse.ui.internal.intro.impl.model.IntroElement#getType()
     */
    public int getType() {
        return AbstractIntroElement.IMAGE;
    }

    /**
     * @return Returns the class id.
     */
    public String getClassId() {
        return super.class_id;
    }

}