package de.sikeller.aqs.visualization;

import com.formdev.flatlaf.FlatLightLaf;
import java.net.URL;
import java.util.Objects;
import javax.swing.*;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public abstract class AbstractVisualization extends Thread {
  protected final JFrame frame;

  protected AbstractVisualization(String title) {
    FlatLightLaf.setup();
    frame = new JFrame(title);
    setIcon();
  }

  protected void setIcon() {
    URL iconURL = this.getClass().getResource("/icon-sm.png");
    ImageIcon icon = new ImageIcon(Objects.requireNonNull(iconURL));
    frame.setIconImage(icon.getImage());
  }
}
