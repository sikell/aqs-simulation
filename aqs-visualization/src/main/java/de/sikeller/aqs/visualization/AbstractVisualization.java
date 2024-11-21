package de.sikeller.aqs.visualization;

import javax.swing.*;
import java.net.URL;
import java.util.Objects;

public abstract class AbstractVisualization {
  protected final JFrame frame;

  protected AbstractVisualization(String title) {
    frame = new JFrame(title);
    setIcon();
  }

  protected void setIcon() {
    URL iconURL = this.getClass().getResource("/icon-sm.png");
    ImageIcon icon = new ImageIcon(Objects.requireNonNull(iconURL));
    frame.setIconImage(icon.getImage());
  }
}
