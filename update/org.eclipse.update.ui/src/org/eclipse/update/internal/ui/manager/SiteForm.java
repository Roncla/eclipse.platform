package org.eclipse.update.internal.ui.manager;
/*
 * (c) Copyright IBM Corp. 2000, 2001.
 * All Rights Reserved.
 */
import org.eclipse.update.internal.ui.parts.*;
import org.eclipse.update.internal.ui.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.update.ui.forms.internal.*;
import org.eclipse.swt.layout.*;
import org.eclipse.ui.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.*;
import org.eclipse.update.core.*;
import org.eclipse.update.internal.ui.model.*;
import org.eclipse.swt.custom.BusyIndicator;
import java.net.URL;
import java.net.MalformedURLException;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.update.ui.forms.internal.engine.FormEngine;
import org.eclipse.jface.action.IStatusLineManager;
public class SiteForm extends UpdateWebForm {
	private static final String KEY_DESC = "SitePage.desc";
	private static final String KEY_LINK = "SitePage.link";
	private Label url;
	private ISiteAdapter currentSite;
	private Image updateSitesImage;
	private static final String KEY_UPDATE_SITES_IMAGE = "updateSites";
	
public SiteForm(UpdateFormPage page) {
	super(page);
	updateSitesImage = UpdateUIPluginImages.DESC_SITES_VIEW.createImage();
}

public void dispose() {
	updateSitesImage.dispose();
	super.dispose();
}

public void initialize(Object modelObject) {
	setHeadingText("");
	setHeadingImage(UpdateUIPluginImages.get(UpdateUIPluginImages.IMG_FORM_BANNER));
	setHeadingUnderlineImage(UpdateUIPluginImages.get(UpdateUIPluginImages.IMG_FORM_UNDERLINE));
	super.initialize(modelObject);
	//((Composite)getControl()).layout(true);
}

protected void createContents(Composite parent) {
	HTMLTableLayout layout = new HTMLTableLayout();
	parent.setLayout(layout);
	layout.leftMargin = layout.rightMargin = 10;
	layout.topMargin = 10;
	layout.horizontalSpacing = 0;
	layout.verticalSpacing = 20;
	layout.numColumns = 1;
	
	FormWidgetFactory factory = getFactory();
	url = factory.createHeadingLabel(parent, null);
	
	FormEngine desc = factory.createFormEngine(parent);
	desc.registerTextObject(KEY_UPDATE_SITES_IMAGE, updateSitesImage);
	desc.load(UpdateUIPlugin.getResourceString(KEY_DESC), true, true);
	TableData td = new TableData();
	td.align = TableData.FILL;
	td.grabHorizontal= true;
	desc.setLayoutData(td);

	IHyperlinkListener listener;
	IActionBars bars = getPage().getView().getViewSite().getActionBars();
	final IStatusLineManager manager = bars.getStatusLineManager();

	listener = new HyperlinkAdapter() {
		public void linkEntered(Control link) {
			ISite site = currentSite.getSite();
			if (site!=null) {
				URL infoURL = site.getInfoURL(); // do not show callback string
				if (infoURL!=null) {
					manager.setMessage(infoURL.toString());
				}
			}
		}
		public void linkExited(Control link) {
			manager.setMessage(null);
		}
		public void linkActivated(Control link) {
			if (currentSite==null) return;
			BusyIndicator.showWhile(getControl().getDisplay(),
			new Runnable() {
				public void run() {
					ISite site = currentSite.getSite();
					if (site!=null) {
						URL infoURL = getURLforSite(site); // navigate with callback string
						if (infoURL!=null) {
							DetailsView dv = (DetailsView)getPage().getView();
							dv.showURL(infoURL.toString());
						}
					}
				}
			});
		}
		public URL getURLforSite(ISite site) {
			URL link = site.getInfoURL();
			String callback = WebInstallHandler.getCallbackString();
			if (callback == null)
				return link;
			try {
				return new URL(link.toExternalForm()+callback);
			} catch (MalformedURLException e) {
				return link;
			}
		}
	};	
	SelectableFormLabel link = new SelectableFormLabel(parent, SWT.NULL);
	link.setText(UpdateUIPlugin.getResourceString(KEY_LINK));
	factory.turnIntoHyperlink(link, listener);
}

public void expandTo(Object obj) {
	if (obj instanceof ISiteAdapter) {
		inputChanged((ISiteAdapter)obj);
	}
}

private void inputChanged(ISiteAdapter site) {
	setHeadingText(site.getLabel());
	url.setText(site.getURL().toString());
	url.getParent().layout();
	((Composite)getControl()).layout();
	getControl().redraw();
	currentSite = site;
}

}