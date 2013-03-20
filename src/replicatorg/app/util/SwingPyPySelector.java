package replicatorg.app.util;

import java.awt.Container;
import java.awt.Frame;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.Vector;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JOptionPane;
import javax.swing.ListSelectionModel;

import net.miginfocom.swing.MigLayout;
import replicatorg.app.Base;
import replicatorg.app.util.PythonUtils.Selector;

public class SwingPyPySelector implements Selector {
	private Frame frame;
	
	public String selectPythonPath(Vector<String> candidates) { return null; }

	public SwingPyPySelector(Frame frame) {
		this.frame = frame;
	}

	public String selectFreeformPath() {
		JFileChooser chooser = new JFileChooser();
		chooser.setDialogTitle("Select installed PyPy binary");
		chooser.setDialogType(JFileChooser.OPEN_DIALOG);
		chooser.setApproveButtonText("Select");
		if (chooser.showOpenDialog(this.frame) == JFileChooser.APPROVE_OPTION) {
			File chosen = chooser.getSelectedFile();
			if (chosen != null) {
				return chosen.getAbsolutePath();
			}
		}
		return null;
	}

}
