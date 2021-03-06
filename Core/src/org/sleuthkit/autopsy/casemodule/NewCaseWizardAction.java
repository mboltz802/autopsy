/*
 * Autopsy Forensic Browser
 *
 * Copyright 2011-2016 Basis Technology Corp.
 * Contact: carrier <at> sleuthkit <dot> org
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.sleuthkit.autopsy.casemodule;

import java.awt.Component;
import java.awt.Dialog;
import java.io.File;
import java.text.MessageFormat;
import java.util.logging.Level;
import javax.swing.JComponent;
import javax.swing.SwingWorker;
import javax.swing.SwingUtilities;
import org.openide.DialogDescriptor;
import org.openide.DialogDisplayer;
import org.openide.NotifyDescriptor;
import org.openide.WizardDescriptor;
import org.openide.util.HelpCtx;
import org.openide.util.NbBundle;
import org.openide.util.actions.CallableSystemAction;
import org.openide.util.actions.SystemAction;
import org.sleuthkit.autopsy.coreutils.Logger;
import javax.swing.JOptionPane;
import org.sleuthkit.autopsy.casemodule.Case.CaseType;
import org.openide.windows.WindowManager;
import java.awt.Cursor;
import java.util.concurrent.ExecutionException;
import org.sleuthkit.autopsy.ingest.IngestManager;

/**
 * An action that creates and runs the new case wizard.
 */
final class NewCaseWizardAction extends CallableSystemAction {

    private static final long serialVersionUID = 1L;
    private static final Logger logger = Logger.getLogger(NewCaseWizardAction.class.getName());
    private WizardDescriptor.Panel<WizardDescriptor>[] panels;

    @Override
    public void performAction() {
        /*
         * If ingest is running, do a dialog to warn the user and confirm the
         * intent to close the current case and leave the ingest process
         * incomplete.
         */
        if (IngestManager.getInstance().isIngestRunning()) {
            NotifyDescriptor descriptor = new NotifyDescriptor.Confirmation(
                    NbBundle.getMessage(this.getClass(), "CloseCaseWhileIngesting.Warning"),
                    NbBundle.getMessage(this.getClass(), "CloseCaseWhileIngesting.Warning.title"),
                    NotifyDescriptor.YES_NO_OPTION, NotifyDescriptor.WARNING_MESSAGE);
            descriptor.setValue(NotifyDescriptor.NO_OPTION);
            Object res = DialogDisplayer.getDefault().notify(descriptor);
            if (res != null && res == DialogDescriptor.YES_OPTION) {
                Case currentCase = null;
                try {
                    currentCase = Case.getCurrentCase();
                    currentCase.closeCase();
                } catch (IllegalStateException ignored) {
                    /*
                     * No current case.
                     */
                } catch (CaseActionException ex) {
                    logger.log(Level.SEVERE, String.format("Error closing case at %s while ingest was running", (null != currentCase ? currentCase.getCaseDirectory() : "?")), ex); //NON-NLS
                }
            } else {
                return;
            }
        }
        WindowManager.getDefault().getMainWindow().setCursor(Cursor.getPredefinedCursor(Cursor.WAIT_CURSOR));
        runNewCaseWizard();
    }

    private void runNewCaseWizard() {
        final WizardDescriptor wizardDescriptor = new WizardDescriptor(getNewCaseWizardPanels());
        wizardDescriptor.setTitleFormat(new MessageFormat("{0}"));
        wizardDescriptor.setTitle(NbBundle.getMessage(this.getClass(), "NewCaseWizardAction.newCase.windowTitle.text"));
        Dialog dialog = DialogDisplayer.getDefault().createDialog(wizardDescriptor);
        dialog.setVisible(true);
        dialog.toFront();
        if (wizardDescriptor.getValue() == WizardDescriptor.FINISH_OPTION) {
            new SwingWorker<Void, Void>() {
                @Override
                protected Void doInBackground() throws Exception {
                    String caseNumber = (String) wizardDescriptor.getProperty("caseNumber"); //NON-NLS
                    String examiner = (String) wizardDescriptor.getProperty("caseExaminer"); //NON-NLS
                    final String caseName = (String) wizardDescriptor.getProperty("caseName"); //NON-NLS
                    String createdDirectory = (String) wizardDescriptor.getProperty("createdDirectory"); //NON-NLS
                    CaseType caseType = CaseType.values()[(int) wizardDescriptor.getProperty("caseType")]; //NON-NLS
                    Case.create(createdDirectory, caseName, caseNumber, examiner, caseType);
                    return null;
                }

                @Override
                protected void done() {
                    try {
                        get();
                        AddImageAction addImageAction = SystemAction.get(AddImageAction.class);
                        addImageAction.actionPerformed(null);
                    } catch (Exception ex) {
                        logger.log(Level.SEVERE, String.format("Error creating case %s", wizardDescriptor.getProperty("caseName")), ex); //NON-NLS                                                
                        SwingUtilities.invokeLater(() -> {
                            JOptionPane.showMessageDialog(
                                    WindowManager.getDefault().getMainWindow(),
                                    (ex instanceof ExecutionException ? ex.getCause().getMessage() : ex.getMessage()),
                                    NbBundle.getMessage(this.getClass(), "CaseCreateAction.msgDlg.cantCreateCase.msg"), //NON-NLS
                                    JOptionPane.ERROR_MESSAGE);
                            StartupWindowProvider.getInstance().close(); // RC: Why close and open?
                            if (!Case.isCaseOpen()) {
                                StartupWindowProvider.getInstance().open();
                            }
                        });
                        doFailedCaseCleanup(wizardDescriptor);
                    }
                }
            }.execute();
        } else {
            new Thread(() -> {
                doFailedCaseCleanup(wizardDescriptor);
            }).start();
        }
    }

    private void doFailedCaseCleanup(WizardDescriptor wizardDescriptor) {
        String createdDirectory = (String) wizardDescriptor.getProperty("createdDirectory"); //NON-NLS
        if (createdDirectory != null) {
            Case.deleteCaseDirectory(new File(createdDirectory));
        }
        SwingUtilities.invokeLater(() -> {
            WindowManager.getDefault().getMainWindow().setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
        });
    }

    /**
     * Creates the new case wizard panels.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private WizardDescriptor.Panel<WizardDescriptor>[] getNewCaseWizardPanels() {
        if (panels == null) {
            panels = new WizardDescriptor.Panel[]{
                new NewCaseWizardPanel1(),
                new NewCaseWizardPanel2()
            };
            String[] steps = new String[panels.length];
            for (int i = 0; i < panels.length; i++) {
                Component c = panels[i].getComponent();
                // Default step name to component name of panel. Mainly useful
                // for getting the name of the target chooser to appear in the
                // list of steps.
                steps[i] = c.getName();
                if (c instanceof JComponent) { // assume Swing components
                    JComponent jc = (JComponent) c;
                    // Sets step number of a component
                    jc.putClientProperty("WizardPanel_contentSelectedIndex", i);
                    // Sets steps names for a panel
                    jc.putClientProperty("WizardPanel_contentData", steps);
                    // Turn on subtitle creation on each step
                    jc.putClientProperty("WizardPanel_autoWizardStyle", Boolean.TRUE);
                    // Show steps on the left side with the image on the background
                    jc.putClientProperty("WizardPanel_contentDisplayed", Boolean.TRUE);
                    // Turn on numbering of all steps
                    jc.putClientProperty("WizardPanel_contentNumbered", Boolean.TRUE);
                }
            }
        }
        return panels;
    }

    /**
     * @inheritDoc
     */
    @Override
    public String getName() {
        return NbBundle.getMessage(this.getClass(), "NewCaseWizardAction.getName.text");
    }

    /**
     * @inheritDoc
     */
    @Override
    public String iconResource() {
        return null;
    }

    /**
     * @inheritDoc
     */
    @Override
    public HelpCtx getHelpCtx() {
        return HelpCtx.DEFAULT_HELP;
    }

    /**
     * @inheritDoc
     */
    @Override
    protected boolean asynchronous() {
        return false;
    }
}
