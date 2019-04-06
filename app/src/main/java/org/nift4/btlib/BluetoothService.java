/*
 * Original (Android Example BluetoothChat): Copyright (C) 2014 The Android Open Source Project
 * Modified Parts: Copyright (C) 2019 nift4
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.nift4.btlib;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.os.Bundle;
import android.content.Context;
import android.content.Intent;
import android.content.BroadcastReceiver;
import android.content.IntentFilter;
import android.app.Activity;
import android.view.View;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;
import android.widget.ListView;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;


/**
 * This class does all the work for setting up and managing Bluetooth
 * connections with other devices. It has a thread that listens for
 * incoming connections, a thread for connecting with a device, and a
 * thread for performing data transmissions when connected.
 */
public class BluetoothService
{
	Callback mCallback;

	public static final int NONE_REASON_STOP = 0;
	public static final int NONE_REASON_CONN_LOST = 1;
	public static final int NONE_REASON_CONN_FAIL = 2;
	public static final int NONE_REASON_NOT_STARTED = 3;
	
	public static interface Callback {
		public void callback();
	}
	public void enableBluetooth(Callback x)
	{
		mCallback = x;
		Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
		mContext.startActivityForResult(enableIntent, 2);
	}
	public void showDeviceSelector()
	{
		Intent clientIntent = new Intent(mContext, BluetoothService.DeviceListActivity.class);
		mContext.startActivityForResult(clientIntent, 1);
	}
	public BluetoothDevice getDeviceSelectorResult(int requestCode, int resultCode, Intent data)
	{
		if (requestCode == 1 && resultCode == mContext.RESULT_OK) {
			String address = data.getExtras().getString(DeviceListActivity.EXTRA_DEVICE_ADDRESS);
			BluetoothDevice device = mAdapter.getRemoteDevice(address);
			return device;
		} else if (requestCode == 2) {
			mCallback.callback();
			mCallback = null;
			return null;
		} else {
			return null;
		}
	}
	public static class BtData {
		public int bytes;
		public byte[] buffer;
	}
	public static class NoBluetoothAdapterFoundException extends Exception {
		public NoBluetoothAdapterFoundException(String e) {
			super(e);
		}
	}
	public static class BluetoothDisabledException extends Exception {
		public BluetoothDisabledException(String e) {
			super(e);
		}
	}
    // Debugging
    private static final String TAG = "BluetoothService";

    // Name for the SDP record when creating server socket
    private static final String NAME_SECURE = "BTLibSecure";
    private static final String NAME_INSECURE = "BTLibInsecure";

    // Unique UUID for this application
    private static UUID MY_UUID_SECURE;
    private static UUID MY_UUID_INSECURE;

    // Member fields
    private final BluetoothAdapter mAdapter;
    private final ArrayBlockingQueue<BtData> mQueue = new ArrayBlockingQueue<BtData>(1024);
    private AcceptThread mSecureAcceptThread;
    private AcceptThread mInsecureAcceptThread;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private int mState;
    private int mNewState;
	private int mNoneReason;
	private Activity mContext;

    // Constants that indicate the current connection state
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device

    /**
     * Constructor. Prepares a new Bluetooth session.
     *
     * @param context The UI Activity Context
     * @param secureUuid The UUID used for secure connections
	 * @param insecureUuid The UUID used for insecure connections
     */
    public BluetoothService(Activity context, UUID secureUuid, UUID insecureUuid) throws BluetoothService.NoBluetoothAdapterFoundException, BluetoothService.BluetoothDisabledException {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
		if (mAdapter == null) {
			throw new NoBluetoothAdapterFoundException("No bluetooth adapter found.");
		}
		if(!mAdapter.isEnabled()){
			throw new BluetoothDisabledException("Bluetooth isn't enabled and the user won't enable it.");
		}
        mState = STATE_NONE;
		mNoneReason = NONE_REASON_NOT_STARTED;
        mNewState = mState;
		mContext = context;
		MY_UUID_INSECURE = insecureUuid;
		MY_UUID_SECURE = secureUuid;
    }
	public BluetoothService(Activity context, UUID secureUuid, UUID insecureUuid,final Callback enablerCallback, final Callback failedCallback) throws BluetoothService.NoBluetoothAdapterFoundException, BluetoothService.BluetoothDisabledException {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
		if (mAdapter == null) {
			throw new NoBluetoothAdapterFoundException("No bluetooth adapter found.");
		}
        mState = STATE_NONE;
		mNoneReason = NONE_REASON_NOT_STARTED;
        mNewState = mState;
		mContext = context;
		MY_UUID_INSECURE = insecureUuid;
		MY_UUID_SECURE = secureUuid;
		enableBluetooth(new Callback(){
				@Override
				public void callback()
				{
					if (mAdapter.isEnabled()) {
						enablerCallback.callback();
					} else {
						failedCallback.callback();
					}
				}
		});
    }
	
	public boolean newMessageAvailable() {
		return mQueue.peek() != null;
	}
	public BtData read() {
		return mQueue.poll();
	}
	
	public int getNoneReason() {
		return mNoneReason;
	}


    /**
     * Return the current connection state.
     */
    public synchronized int getState() {
        return mState;
    }

    /**
     * Start the chat service. Specifically start AcceptThread to begin a
     * session in listening (server) mode. Called by the Activity onResume()
     */
    public synchronized void startServer() {
        System.out.println("start");

        // Cancel any thread attempting to make a connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start the thread to listen on a BluetoothServerSocket
        if (mSecureAcceptThread == null) {
            mSecureAcceptThread = new AcceptThread(true);
            mSecureAcceptThread.start();
        }
        if (mInsecureAcceptThread == null) {
            mInsecureAcceptThread = new AcceptThread(false);
            mInsecureAcceptThread.start();
        }
    }

    /**
     * Start the ConnectThread to initiate a connection to a remote device.
     *
     * @param device The BluetoothDevice to connect
     * @param secure Socket Security type - Secure (true) , Insecure (false)
     */
    public synchronized void connect(BluetoothDevice device, boolean secure) {
        System.out.println("connect to: " + device);

        // Cancel any thread attempting to make a connection
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Start the thread to connect with the given device
        mConnectThread = new ConnectThread(device, secure);
        mConnectThread.start();
    }

    /**
     * Start the ConnectedThread to begin managing a Bluetooth connection
     *
     * @param socket The BluetoothSocket on which the connection was made
     * @param device The BluetoothDevice that has been connected
     */
    private synchronized void connected(BluetoothSocket socket, BluetoothDevice
									   device, final String socketType) {
        System.out.println("connected, Socket Type:" + socketType);

        // Cancel the thread that completed the connection
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        // Cancel any thread currently running a connection
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        // Cancel the accept thread because we only want to connect to one device
        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }
        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.cancel();
            mInsecureAcceptThread = null;
        }

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket, socketType);
        mConnectedThread.start();
    }
	
	public void makeDiscoverable() {
		if (mAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent myIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            myIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            mContext.startActivity(myIntent);
        }
	}
	
    /**
     * Stop all threads
     */
    public synchronized void stop() {
        System.out.println("stop");

        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }

        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        if (mSecureAcceptThread != null) {
            mSecureAcceptThread.cancel();
            mSecureAcceptThread = null;
        }

        if (mInsecureAcceptThread != null) {
            mInsecureAcceptThread.cancel();
            mInsecureAcceptThread = null;
        }
        mState = STATE_NONE;
		mNoneReason = NONE_REASON_STOP;
    }

    /**
     * Write to the ConnectedThread in an unsynchronized manner
     *
     * @param out The bytes to write
     * @see ConnectedThread#write(byte[])
     */
    public void write(byte[] out) {
        // Create temporary object
        ConnectedThread r;
        // Synchronize a copy of the ConnectedThread
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        // Perform the write unsynchronized
        r.write(out);
    }
	
	public void writeSync(byte[] out) {
		synchronized(this){
			mConnectedThread.write(out);
		}
	}

    /**
     * Indicate that the connection attempt failed and notify the UI Activity.
     */
    private void connectionFailed() {
		System.out.println("connFail");
		synchronized(this){
			mState = STATE_NONE;
			mNoneReason = NONE_REASON_CONN_FAIL;
		}
    }

    /**
     * Indicate that the connection was lost and notify the UI Activity.
     */
    private void connectionLost() {
        System.out.println("connLost");
		synchronized(this){
			mState = STATE_NONE;
			mNoneReason = NONE_REASON_CONN_LOST;
		}
    }

    /**
     * This thread runs while listening for incoming connections. It behaves
     * like a server-side client. It runs until a connection is accepted
     * (or until cancelled).
     */
    private class AcceptThread extends Thread {
        // The local server socket
        private final BluetoothServerSocket mmServerSocket;
        private String mSocketType;

        public AcceptThread(boolean secure) {
            BluetoothServerSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";

            // Create a new listening server socket
            try {
                if (secure) {
                    tmp = mAdapter.listenUsingRfcommWithServiceRecord(NAME_SECURE,
																	  MY_UUID_SECURE);
                } else {
                    tmp = mAdapter.listenUsingInsecureRfcommWithServiceRecord(
						NAME_INSECURE, MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                System.err.println("Socket Type: " + mSocketType + "listen() failed");
				e.printStackTrace();
            }
            mmServerSocket = tmp;
            mState = STATE_LISTEN;
        }

        public void run() {
            System.out.println("Socket Type: " + mSocketType +
				  "BEGIN mAcceptThread" + this);
            setName("AcceptThread" + mSocketType);

            BluetoothSocket socket = null;

            // Listen to the server socket if we're not connected
            while (mState != STATE_CONNECTED) {
                try {
                    // This is a blocking call and will only return on a
                    // successful connection or an exception
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    System.err.println("Socket Type: " + mSocketType + "accept() failed");
					e.printStackTrace();
                    break;
                }

                // If a connection was accepted
                if (socket != null) {
                    synchronized (BluetoothService.this) {
                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                // Situation normal. Start the connected thread.
                                connected(socket, socket.getRemoteDevice(),
										  mSocketType);
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                                // Either not ready or already connected. Terminate new socket.
                                try {
                                    socket.close();
                                } catch (IOException e) {
                                    System.err.println("Could not close unwanted socket");
									e.printStackTrace();
                                }
								System.out.println("Already connected.");
                                break;
                        }
                    }
                }
            }
            System.out.println("END mAcceptThread, socket Type: " + mSocketType);

        }

        public void cancel() {
            System.out.println("Socket Type" + mSocketType + "cancel " + this);
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                System.err.println("Socket Type" + mSocketType + "close() of server failed");
				e.printStackTrace();
            }
        }
    }


    /**
     * This thread runs while attempting to make an outgoing connection
     * with a device. It runs straight through; the connection either
     * succeeds or fails.
     */
    private class ConnectThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private String mSocketType;

        public ConnectThread(BluetoothDevice device, boolean secure) {
            mmDevice = device;
            BluetoothSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";

            // Get a BluetoothSocket for a connection with the
            // given BluetoothDevice
            try {
                if (secure) {
                    tmp = device.createRfcommSocketToServiceRecord(
						MY_UUID_SECURE);
                } else {
                    tmp = device.createInsecureRfcommSocketToServiceRecord(
						MY_UUID_INSECURE);
                }
            } catch (IOException e) {
                System.err.println("Socket Type: " + mSocketType + "create() failed");
				e.printStackTrace();
            }
            mmSocket = tmp;
            mState = STATE_CONNECTING;
        }

        public void run() {
            System.out.println("BEGIN mConnectThread SocketType:" + mSocketType);
            setName("ConnectThread" + mSocketType);

            // Always cancel discovery because it will slow down a connection
            mAdapter.cancelDiscovery();

            // Make a connection to the BluetoothSocket
            try {
                // This is a blocking call and will only return on a
                // successful connection or an exception
                mmSocket.connect();
            } catch (IOException e) {
                // Close the socket
                try {
                    mmSocket.close();
                } catch (IOException e2) {
					e2.printStackTrace();
                    System.err.println("unable to close() " + mSocketType +
						  " socket during connection failure");
                }
                connectionFailed();
                return;
            }

            // Reset the ConnectThread because we're done
            synchronized (BluetoothService.this) {
                mConnectThread = null;
            }

            // Start the connected thread
            connected(mmSocket, mmDevice, mSocketType);
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
				e.printStackTrace();
                System.err.println("close() of connect " + mSocketType + " socket failed");
            }
        }
    }

    /**
     * This thread runs during a connection with a remote device.
     * It handles all incoming and outgoing transmissions.
     */
    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket, String socketType) {
            System.out.println("create ConnectedThread: " + socketType);
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            // Get the BluetoothSocket input and output streams
            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
				e.printStackTrace();
                System.err.println("temp sockets not created");
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
            mState = STATE_CONNECTED;
        }

        public void run() {
            System.out.println("BEGIN mConnectedThread");
            byte[] buffer = new byte[1024];
            int bytes;

            // Keep listening to the InputStream while connected
            while (mState == STATE_CONNECTED) {
                try {
                    // Read from the InputStream
                    bytes = mmInStream.read(buffer);
                    // Send the obtained bytes to the UI Activity
					bytesRead(bytes, buffer);
                } catch (IOException e) {
					e.printStackTrace();
                    System.err.println("disconnected");
                    connectionLost();
                    break;
                }
            }
        }

		private void bytesRead(int bytes, byte[] buffer)
		{
			BtData d = new BtData();
			d.bytes = bytes;
			d.buffer = buffer.clone();
			try {
				mQueue.put(d);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}

        /**
         * Write to the connected OutStream.
         *
         * @param buffer The bytes to write
         */
        public void write(byte[] buffer) {
            try {
                mmOutStream.write(buffer);
            } catch (IOException e) {
				e.printStackTrace();
                System.err.println("Exception during write");
            }
        }

        public void cancel() {
            try {
                mmSocket.close();
            } catch (IOException e) {
				e.printStackTrace();
                System.err.println("close() of connect socket failed");
            }
        }
    }
	public static class DeviceListActivity extends Activity {

		/**
		 * Tag for Log
		 */
		private static final String TAG = "DeviceListActivity";

		/**
		 * Return Intent extra
		 */
		public static String EXTRA_DEVICE_ADDRESS = "device_address";

		/**
		 * Member fields
		 */
		private BluetoothAdapter mBtAdapter;

		/**
		 * Newly discovered devices
		 */
		private ArrayAdapter<String> mNewDevicesArrayAdapter;

		@Override
		protected void onCreate(Bundle savedInstanceState) {
			super.onCreate(savedInstanceState);

			// Setup the window
			requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
			setContentView(R.layout.activity_device_list);

			// Set result CANCELED in case the user backs out
			setResult(Activity.RESULT_CANCELED);

			// Initialize the button to perform device discovery
			Button scanButton = (Button) findViewById(R.id.button_scan);
			scanButton.setOnClickListener(new View.OnClickListener() {
					public void onClick(View v) {
						doDiscovery();
						v.setVisibility(View.GONE);
					}
				});

			// Initialize array adapters. One for already paired devices and
			// one for newly discovered devices
			ArrayAdapter<String> pairedDevicesArrayAdapter =
				new ArrayAdapter<String>(this, R.layout.device_name);
			mNewDevicesArrayAdapter = new ArrayAdapter<String>(this, R.layout.device_name);

			// Find and set up the ListView for paired devices
			ListView pairedListView = (ListView) findViewById(R.id.paired_devices);
			pairedListView.setAdapter(pairedDevicesArrayAdapter);
			pairedListView.setOnItemClickListener(mDeviceClickListener);

			// Find and set up the ListView for newly discovered devices
			ListView newDevicesListView = (ListView) findViewById(R.id.new_devices);
			newDevicesListView.setAdapter(mNewDevicesArrayAdapter);
			newDevicesListView.setOnItemClickListener(mDeviceClickListener);

			// Register for broadcasts when a device is discovered
			IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
			this.registerReceiver(mReceiver, filter);

			// Register for broadcasts when discovery has finished
			filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
			this.registerReceiver(mReceiver, filter);

			// Get the local Bluetooth adapter
			mBtAdapter = BluetoothAdapter.getDefaultAdapter();

			// Get a set of currently paired devices
			Set<BluetoothDevice> pairedDevices = mBtAdapter.getBondedDevices();

			// If there are paired devices, add each one to the ArrayAdapter
			if (pairedDevices.size() > 0) {
				findViewById(R.id.title_paired_devices).setVisibility(View.VISIBLE);
				for (BluetoothDevice device : pairedDevices) {
					pairedDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
				}
			} else {
				String noDevices = getResources().getText(R.string.none_paired).toString();
				pairedDevicesArrayAdapter.add(noDevices);
			}
		}

		@Override
		protected void onDestroy() {
			super.onDestroy();

			// Make sure we're not doing discovery anymore
			if (mBtAdapter != null) {
				mBtAdapter.cancelDiscovery();
			}

			// Unregister broadcast listeners
			this.unregisterReceiver(mReceiver);
		}

		/**
		 * Start device discover with the BluetoothAdapter
		 */
		private void doDiscovery() {
			System.out.println("doDiscovery()");

			// Indicate scanning in the title
			setProgressBarIndeterminateVisibility(true);
			setTitle(R.string.scanning);

			// Turn on sub-title for new devices
			findViewById(R.id.title_new_devices).setVisibility(View.VISIBLE);

			// If we're already discovering, stop it
			if (mBtAdapter.isDiscovering()) {
				mBtAdapter.cancelDiscovery();
			}

			// Request discover from BluetoothAdapter
			mBtAdapter.startDiscovery();
		}

		/**
		 * The on-click listener for all devices in the ListViews
		 */
		private AdapterView.OnItemClickListener mDeviceClickListener
		= new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> av, View v, int arg2, long arg3) {
				// Cancel discovery because it's costly and we're about to connect
				mBtAdapter.cancelDiscovery();

				// Get the device MAC address, which is the last 17 chars in the View
				String info = ((TextView) v).getText().toString();
				String address = info.substring(info.length() - 17);

				// Create the result Intent and include the MAC address
				Intent intent = new Intent();
				intent.putExtra(EXTRA_DEVICE_ADDRESS, address);

				// Set result and finish this Activity
				setResult(Activity.RESULT_OK, intent);
				finish();
			}
		};

		/**
		 * The BroadcastReceiver that listens for discovered devices and changes the title when
		 * discovery is finished
		 */
		private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				String action = intent.getAction();

				// When discovery finds a device
				if (BluetoothDevice.ACTION_FOUND.equals(action)) {
					// Get the BluetoothDevice object from the Intent
					BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
					// If it's already paired, skip it, because it's been listed already
					if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
						mNewDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
					}
					// When discovery is finished, change the Activity title
				} else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
					setProgressBarIndeterminateVisibility(false);
					setTitle(R.string.select_device);
					if (mNewDevicesArrayAdapter.getCount() == 0) {
						String noDevices = getResources().getText(R.string.none_found).toString();
						mNewDevicesArrayAdapter.add(noDevices);
					}
				}
			}
		};

	}
	
	public static class AboutActivity extends Activity {
		
	}
}
