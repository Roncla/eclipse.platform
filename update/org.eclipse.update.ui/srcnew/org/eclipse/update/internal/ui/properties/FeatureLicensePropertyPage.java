package org.eclipse.update.internal.ui.properties;

import java.net.URL;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.swt.SWT;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.dialogs.PropertyPage;
import org.eclipse.ui.IWorkbenchPropertyPage;
import org.eclipse.update.core.IFeature;
import org.eclipse.update.core.IURLEntry;
import org.eclipse.update.internal.ui.model.IFeatureAdapter;
import org.eclipse.update.internal.ui.parts.SWTUtil;

public class FeatureLicensePropertyPage extends PropertyPage implements IWorkbenchPropertyPage {
	public FeatureLicensePropertyPage() {
		noDefaultAndApplyButton();
	}

	protected Control createContents(Composite parent)  {
		try {
			Composite composite = new Composite(parent, SWT.NULL);
			composite.setLayout(new GridLayout());

			IFeatureAdapter adapter = (IFeatureAdapter)getElement();
			IFeature feature = adapter.getFeature(null);			
			IURLEntry license = feature.getLicense();
			String annotation = (license != null) ? license.getAnnotation() : null;
			
			if (annotation != null && annotation.length() > 0) {
				Text text = new Text(composite, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER | SWT.WRAP);
				GridData gd = new GridData();
				gd.heightHint = 350;
				gd.widthHint = 350;
				text.setLayoutData(gd);
				text.setText(annotation);
				text.setEditable(false);
				final URL url = license.getURL();
				String filename = (url != null) ? url.getFile() : null;
				if (filename != null && (filename.endsWith(".htm") || url.getFile().endsWith(".html"))) {
					Button button = new Button(composite, SWT.PUSH);
					button.setText("Show in Browser");
					button.setLayoutData(new GridData(GridData.HORIZONTAL_ALIGN_END));
					SWTUtil.setButtonDimensionHint(button);
					button.addSelectionListener(new SelectionAdapter() {
						public void widgetSelected(SelectionEvent e) {
							Program.launch(url.getProtocol() + ":" + url.getFile());
						}
					});
				}
			} else {
				Label label = new Label(composite, SWT.NULL);
				label.setText("Feature does not contain a license.");
			}
			return composite;
			
		} catch (CoreException e) {
		}
		return null;
	}
}
