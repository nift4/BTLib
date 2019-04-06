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

	public static interface Callback
	{
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
	public static class BtData
	{
		public int bytes;
		public byte[] buffer;
	}
	public static class NoBluetoothAdapterFoundException extends Exception
	{
		public NoBluetoothAdapterFoundException(String e)
		{
			super(e);
		}
	}
	public static class BluetoothDisabledException extends Exception
	{
		public BluetoothDisabledException(String e)
		{
			super(e);
		}
	}
    private static final String NAME_SECURE = "BTLibSecure";
    private static final String NAME_INSECURE = "BTLibInsecure";
    private static UUID MY_UUID_SECURE;
    private static UUID MY_UUID_INSECURE;
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
    public static final int STATE_NONE = 0;       // we're doing nothing
    public static final int STATE_LISTEN = 1;     // now listening for incoming connections
    public static final int STATE_CONNECTING = 2; // now initiating an outgoing connection
    public static final int STATE_CONNECTED = 3;  // now connected to a remote device
    public BluetoothService(Activity context, UUID secureUuid, UUID insecureUuid) throws BluetoothService.NoBluetoothAdapterFoundException, BluetoothService.BluetoothDisabledException
	{
        mAdapter = BluetoothAdapter.getDefaultAdapter();
		if (mAdapter == null) {
			throw new NoBluetoothAdapterFoundException("No bluetooth adapter found.");
		}
		if (!mAdapter.isEnabled()) {
			throw new BluetoothDisabledException("Bluetooth isn't enabled and the user won't enable it.");
		}
        mState = STATE_NONE;
		mNoneReason = NONE_REASON_NOT_STARTED;
        mNewState = mState;
		mContext = context;
		MY_UUID_INSECURE = insecureUuid;
		MY_UUID_SECURE = secureUuid;
    }
	public BluetoothService(Activity context, UUID secureUuid, UUID insecureUuid, final Callback enablerCallback, final Callback failedCallback) throws BluetoothService.NoBluetoothAdapterFoundException, BluetoothService.BluetoothDisabledException
	{
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
	public synchronized boolean newMessageAvailable()
	{
		return mQueue.peek() != null;
	}
	public synchronized BtData read()
	{
		return mQueue.poll();
	}

	public synchronized int getNoneReason()
	{
		return mNoneReason;
		
	}
    public synchronized int getState()
	{
        return mState;
    }
    public synchronized void startServer()
	{
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
        if (mSecureAcceptThread == null) {
            mSecureAcceptThread = new AcceptThread(true);
            mSecureAcceptThread.start();
        }
        if (mInsecureAcceptThread == null) {
            mInsecureAcceptThread = new AcceptThread(false);
            mInsecureAcceptThread.start();
        }
    }
    public synchronized void connect(BluetoothDevice device, boolean secure)
	{
        if (mState == STATE_CONNECTING) {
            if (mConnectThread != null) {
                mConnectThread.cancel();
                mConnectThread = null;
            }
        }
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }

        mConnectThread = new ConnectThread(device, secure);
        mConnectThread.start();
    }
    private synchronized void connected(BluetoothSocket socket, BluetoothDevice
										device, final String socketType)
	{
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
        mConnectedThread = new ConnectedThread(socket, socketType);
        mConnectedThread.start();
    }
	public void makeDiscoverable()
	{
		if (mAdapter.getScanMode() != BluetoothAdapter.SCAN_MODE_CONNECTABLE_DISCOVERABLE) {
            Intent myIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE);
            myIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300);
            mContext.startActivity(myIntent);
        }
	}
    public synchronized void stop()
	{
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
    public void write(byte[] out)
	{
        ConnectedThread r;
        synchronized (this) {
            if (mState != STATE_CONNECTED) return;
            r = mConnectedThread;
        }
        r.write(out);
    }
    private void connectionFailed(){
		synchronized (this) {
			mState = STATE_NONE;
			mNoneReason = NONE_REASON_CONN_FAIL;
		}
    }
    private void connectionLost()
	{
		synchronized (this) {
			mState = STATE_NONE;
			mNoneReason = NONE_REASON_CONN_LOST;
		}
    }
    private class AcceptThread extends Thread
	{
        private final BluetoothServerSocket mmServerSocket;
        private String mSocketType;

        public AcceptThread(boolean secure)
		{
            BluetoothServerSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";
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

        public void run()
		{
            setName("AcceptThread" + mSocketType);

            BluetoothSocket socket = null;

            while (mState != STATE_CONNECTED) {
                try {
                    socket = mmServerSocket.accept();
                } catch (IOException e) {
                    System.err.println("Socket Type: " + mSocketType + "accept() failed");
					e.printStackTrace();
                    break;
                }
                if (socket != null) {
                    synchronized (BluetoothService.this) {
                        switch (mState) {
                            case STATE_LISTEN:
                            case STATE_CONNECTING:
                                connected(socket, socket.getRemoteDevice(),
										  mSocketType);
                                break;
                            case STATE_NONE:
                            case STATE_CONNECTED:
                               
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

        public void cancel()
		{
            try {
                mmServerSocket.close();
            } catch (IOException e) {
                System.err.println("Socket Type" + mSocketType + "close() of server failed");
				e.printStackTrace();
            }
        }
    }
    private class ConnectThread extends Thread
	{
        private final BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;
        private String mSocketType;

        public ConnectThread(BluetoothDevice device, boolean secure)
		{
            mmDevice = device;
            BluetoothSocket tmp = null;
            mSocketType = secure ? "Secure" : "Insecure";
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

        public void run()
		{
            setName("ConnectThread" + mSocketType);
            mAdapter.cancelDiscovery();
            try {
                mmSocket.connect();
            } catch (IOException e) {
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

            synchronized (BluetoothService.this) {
                mConnectThread = null;
            }

            connected(mmSocket, mmDevice, mSocketType);
        }

        public void cancel()
		{
            try {
                mmSocket.close();
            } catch (IOException e) {
				e.printStackTrace();
                System.err.println("close() of connect " + mSocketType + " socket failed");
            }
        }
    }
    private class ConnectedThread extends Thread
	{
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        public ConnectedThread(BluetoothSocket socket, String socketType)
		{
            System.out.println("create ConnectedThread: " + socketType);
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

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

        public void run()
		{
            System.out.println("BEGIN mConnectedThread");
            byte[] buffer = new byte[1024];
            int bytes;
            while (mState == STATE_CONNECTED) {
                try {
                    bytes = mmInStream.read(buffer);
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
        public void write(byte[] buffer)
		{
            try {
                mmOutStream.write(buffer);
            } catch (IOException e) {
				e.printStackTrace();
                System.err.println("Exception during write");
            }
        }
        public void cancel()
		{
            try {
                mmSocket.close();
            } catch (IOException e) {
				e.printStackTrace();
                System.err.println("close() of connect socket failed");
            }
        }
    }
	public static class DeviceListActivity extends Activity
	{
		public static String EXTRA_DEVICE_ADDRESS = "device_address";
		private BluetoothAdapter mBtAdapter;
		private ArrayAdapter<String> mNewDevicesArrayAdapter;

		@Override
		protected void onCreate(Bundle savedInstanceState)
		{
			super.onCreate(savedInstanceState);

			requestWindowFeature(Window.FEATURE_INDETERMINATE_PROGRESS);
			setContentView(R.layout.activity_device_list);

			setResult(Activity.RESULT_CANCELED);

			Button scanButton = (Button) findViewById(R.id.button_scan);
			scanButton.setOnClickListener(new View.OnClickListener() {
					public void onClick(View v)
					{
						doDiscovery();
						v.setVisibility(View.GONE);
					}
				});
			ArrayAdapter<String> pairedDevicesArrayAdapter =
				new ArrayAdapter<String>(this, R.layout.device_name);
			mNewDevicesArrayAdapter = new ArrayAdapter<String>(this, R.layout.device_name);

			ListView pairedListView = (ListView) findViewById(R.id.paired_devices);
			pairedListView.setAdapter(pairedDevicesArrayAdapter);
			pairedListView.setOnItemClickListener(mDeviceClickListener);

			ListView newDevicesListView = (ListView) findViewById(R.id.new_devices);
			newDevicesListView.setAdapter(mNewDevicesArrayAdapter);
			newDevicesListView.setOnItemClickListener(mDeviceClickListener);

			IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
			this.registerReceiver(mReceiver, filter);
			
			filter = new IntentFilter(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
			this.registerReceiver(mReceiver, filter);
			
			mBtAdapter = BluetoothAdapter.getDefaultAdapter();

			Set<BluetoothDevice> pairedDevices = mBtAdapter.getBondedDevices();

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
		protected void onDestroy()
		{
			super.onDestroy();

			if (mBtAdapter != null) {
				mBtAdapter.cancelDiscovery();
			}

			this.unregisterReceiver(mReceiver);
		}

		private void doDiscovery()
		{
			setProgressBarIndeterminateVisibility(true);
			setTitle(R.string.scanning);

			findViewById(R.id.title_new_devices).setVisibility(View.VISIBLE);

			if (mBtAdapter.isDiscovering()) {
				mBtAdapter.cancelDiscovery();
			}

			mBtAdapter.startDiscovery();
		}

		private AdapterView.OnItemClickListener mDeviceClickListener
		= new AdapterView.OnItemClickListener() {
			public void onItemClick(AdapterView<?> av, View v, int arg2, long arg3)
			{
				mBtAdapter.cancelDiscovery();

				String info = ((TextView) v).getText().toString();
				String address = info.substring(info.length() - 17);

				Intent intent = new Intent();
				intent.putExtra(EXTRA_DEVICE_ADDRESS, address);
				setResult(Activity.RESULT_OK, intent);
				finish();
			}
		};
		
		private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent)
			{
				String action = intent.getAction();
				if (BluetoothDevice.ACTION_FOUND.equals(action)) {
					BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
					
					if (device.getBondState() != BluetoothDevice.BOND_BONDED) {
						mNewDevicesArrayAdapter.add(device.getName() + "\n" + device.getAddress());
					}
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

	public static class AboutActivity extends Activity
	{
		@Override
		protected void onCreate(Bundle savedInstanceState)
		{
			super.onCreate(savedInstanceState);
			TextView tv = new TextView(this);
			tv.setText("This Application's Bluetooth Services are powered by BTLib\n\n\nLicense:\nOriginal (Android Example BluetoothChat): Copyright (C) 2014 The Android Open Source Project\nModified Parts: Copyright (C) 2019 nift4\nLicensed under the Apache License, Version 2.0 (the \"License\");\nyou may not use this file except in compliance with the License.\nYou may obtain a copy of the License at\n\nhttp://www.apache.org/licenses/LICENSE-2.0\n\nUnless required by applicable law or agreed to in writing, software\ndistributed under the License is distributed on an \"AS IS\" BASIS,\nWITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.\nSee the License for the specific language governing permissions and\nlimitations under the License.");
			setContentView(tv);
		}
	}
}
