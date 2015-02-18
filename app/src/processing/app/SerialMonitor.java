/* -*- mode: java; c-basic-offset: 2; indent-tabs-mode: nil -*- */

/*
  This program is free software; you can redistribute it and/or modify
  it under the terms of the GNU General Public License as published by
  the Free Software Foundation; either version 2 of the License, or
  (at your option) any later version.

  This program is distributed in the hope that it will be useful,
  but WITHOUT ANY WARRANTY; without even the implied warranty of
  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
  GNU General Public License for more details.

  You should have received a copy of the GNU General Public License
  along with this program; if not, write to the Free Software Foundation,
  Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
*/

package processing.app;

import cc.arduino.packages.BoardPort;
import processing.app.legacy.PApplet;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.*;
import java.net.*;
import java.util.*;
import processing.app.helpers.PreferencesMap;

import static processing.app.I18n._;

@SuppressWarnings("serial")
public class SerialMonitor extends AbstractMonitor {

  private Thread onlineChecker;
  private final String port;
  private Serial serial;
  private int serialRate;

  public SerialMonitor(BoardPort port) {
    super(port.getLabel());

    this.port = port.getAddress();

    serialRate = Preferences.getInteger("serial.debug_rate");
    serialRates.setSelectedItem(serialRate + " " + _("baud"));
    onSerialRateChange(new ActionListener() {
      public void actionPerformed(ActionEvent event) {
        String wholeString = (String) serialRates.getSelectedItem();
        String rateString = wholeString.substring(0, wholeString.indexOf(' '));
        serialRate = Integer.parseInt(rateString);
        Preferences.set("serial.debug_rate", rateString);
        try {
          close();
          Thread.sleep(100); // Wait for serial port to properly close
          open();
        } catch (InterruptedException e) {
          // noop
        } catch (Exception e) {
          System.err.println(e);
        }
      }
    });

    onSendCommand(new ActionListener() {
      public void actionPerformed(ActionEvent e) {
        send(textField.getText());
        textField.setText("");
      }
    });
  }

  private void send(String s) {
    if (serial != null) {
      switch (lineEndings.getSelectedIndex()) {
        case 1:
          s += "\n";
          break;
        case 2:
          s += "\r";
          break;
        case 3:
          s += "\r\n";
          break;
      }
      if ("".equals(s) && lineEndings.getSelectedIndex() == 0 && !Preferences.has("runtime.line.ending.alert.notified")) {
        noLineEndingAlert.setForeground(Color.RED);
        Preferences.set("runtime.line.ending.alert.notified", "true");
      }
      serial.write(s);
    }
  }

  public void open() throws Exception {
    if (Thread.currentThread() != reopener) reopen_abort();
    if (serial != null) return;
    if (onlineChecker != null) {
      onlineChecker.interrupt();
    }

    if (BaseNoGui.isTeensyduino()) {
      // Only do this special open stuff if we're absolutely
      // sure the board is Teensy.  Don't risk messing others.
      PreferencesMap prefs = BaseNoGui.getBoardPreferences();
      String fake = prefs.get("fake_serial");
      if (prefs.getBoolean("serial.restart_cmd")) {
        if (fake == null) {
          serial = new Serial(port, 150) {
            @Override
            protected void message(char buff[], int n) {
              addToUpdateBuffer(buff, n);
            }
          };
          serial.setBaud(serialRate);
          textArea.setText("");
          isOpen = true;
          enableWindow(true);
          return;
        } else {
          String cmdline = BaseNoGui.getHardwarePath() + File.separator
	  + "tools" + File.separator + "teensy_restart";
          try {
            Runtime.getRuntime().exec(new String[] {cmdline});
          } catch (Exception e) {
          }
        }
      }
      if (fake != null) {
        serial = new FakeSerial(fake) {
          @Override
          protected void message(char buff[], int n) {
            addToUpdateBuffer(buff, n);
          }
        };
        textArea.setText("");
        isOpen = true;
        enableWindow(true);
        return;
      }
    }
    serial = new Serial(port, serialRate) {
      @Override
      protected void message(char buff[], int n) {
        addToUpdateBuffer(buff, n);
      }
    };
    textArea.setText("");
    isOpen = true;
    enableWindow(true);
    if (BaseNoGui.isTeensyduino()) {
      onlineChecker = new Thread() {
        public void run() {
          while (true) {
            try {
              sleep(400);
            } catch (InterruptedException e) {
              return;
            }
            if (serial == null) return;
            if (!(serial.isOnline())) {
              //System.out.println("Serial went offline");
              try {
                close();
                reopen();
              } catch (Exception e) {
              }
              return;
            }
          }
        }
      };
      onlineChecker.start();
    }
  }

  public void close() throws Exception {
    if (serial != null) {
      int[] location = getPlacement();
      String locationStr = PApplet.join(PApplet.str(location), ",");
      Preferences.set("last.serial.location", locationStr);
      //textArea.setText("");
      serial.dispose();
      serial = null;
    }
    isOpen = false;
    enableWindow(false);
  }
}


class FakeSerial extends Serial {
	Socket sock=null;
	InputStream input;
	OutputStream output;
	inputListener listener=null;
	int[] addrlist = {28541,4984,18924,16924,27183,31091};
	static Process gateway=null;
	static boolean gateway_shutdown_scheduled=false;

	public FakeSerial(String name) throws SerialException {
		super("fake serial"); // prevents normal serial from also opening!
		int attempt=1;
		do {
			if (gateway_connect(name)) {
				listener = new inputListener();
				listener.input = input;
				listener.consumer = this;
				listener.start();
				return;
			}
			if (attempt <= 2 && !gateway_start(name)) {
				System.err.println("Error starting " + name);
			}
			delay_20ms();
		} while (++attempt < 4);
		throw new SerialException("no connection");
	}
	private boolean gateway_connect(String name) {
		int namelen = name.length();
		byte[] buf = new byte[namelen];
		byte[] namebuf = name.getBytes();
		InetAddress local;
		try {
			byte[] loop = new byte[] {127, 0, 0, 1};
			local = InetAddress.getByAddress("localhost", loop);
		} catch (Exception e) {
			sock = null;
			return false;
		}
		for (int i=0; i<addrlist.length; i++) {
			try {
				sock = new Socket();
				InetSocketAddress addr = new InetSocketAddress(local, addrlist[i]);
				sock.connect(addr, 50); // if none, should timeout instantly
							// but windows will wait up to 1 sec!
				input = sock.getInputStream();
				output = sock.getOutputStream();
			} catch (Exception e) {
				sock = null;
				return false;
			}
			// check for welcome message
			try {
				int wait = 0;
				while (input.available() < namelen) {
					if (++wait > 6) throw new Exception();
					delay_20ms();
				}
				input.read(buf, 0, namelen);
				String id = new String(buf, 0, namelen);
				for (int n=0; n<namelen; n++) {
					if (buf[n] !=  namebuf[n]) throw new Exception();
				}
			} catch (Exception e) {
				// mistakenly connected to some other program!
				close_sock();
				continue;
			}
                        //System.out.println("gateway_connect ok");
			return true;
		}
		sock = null;
		return false;
	}
	private void close_sock() {
		try {
			sock.close();
		} catch (Exception e) { }
		sock = null;
	}
	private void delay_20ms() {
		try {
			Thread.sleep(20);
		} catch (Exception e) { }
	}
	public void dispose() {
		if (listener != null) {
			listener.interrupt();
			listener.consumer = null;
			listener = null;
		}
		if (sock != null) {
			try {
				sock.close();
			} catch (Exception e) { }
			sock = null;
		}
	       dispose_gateway();
	}
	public static void dispose_gateway() {
		if (gateway != null) {
			gateway.destroy();
			gateway = null;
		}
	}
	private boolean gateway_start(String cmd) {
		String cmdline = BaseNoGui.getHardwarePath() + File.separator
			+ "tools" + File.separator + cmd;
		try {
			gateway = Runtime.getRuntime().exec(new String[] {cmdline});
			if (!gateway_shutdown_scheduled) {
				Runtime.getRuntime().addShutdownHook(new Thread() {
					public void run() {
						FakeSerial.dispose_gateway();
					}
				});
				gateway_shutdown_scheduled = true;
			}
		} catch (Exception e) {
		       gateway = null;
			return false;
		}
		return true;
	}
	protected void message(char[] chars, int length) {
		// override from SerialMonitor
	}
	public void write(byte bytes[]) {
		if (output == null) return;
		if (bytes.length > 0) {
			try {
				output.write(bytes, 0, bytes.length);
			} catch (IOException e) { }
		}
	}
	public void write(int what) {
		byte[] b = new byte[1];
		b[0] = (byte)(what & 0xff);
		write(b);
	}
	public void setDTR(boolean state) {
	}
	public void setRTS(boolean state) {
	}
	public boolean isOnline() {
		return true;
	}
	static public ArrayList<String> list() {
		return new ArrayList<String>();
	}
}

class inputListener extends Thread {
	FakeSerial consumer = null;
	InputStream input;

	public void run() {
		byte[] buffer = new byte[1024];
		int num, errcount=0;
		try {
			while (true) {
				num = input.read(buffer);
				if (num <= 0) break;
				//System.out.println("inputListener, n = " + num);
				if (consumer != null) {
					String msg = new String(buffer, 0, num);
					char[] chars = msg.toCharArray();
					consumer.message(chars, chars.length);
				}
			}
		} catch (Exception e) { }
	}
}
