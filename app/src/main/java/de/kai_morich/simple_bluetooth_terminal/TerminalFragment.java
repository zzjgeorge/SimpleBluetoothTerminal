package de.kai_morich.simple_bluetooth_terminal;


import static de.kai_morich.simple_bluetooth_terminal.MainActivity.mSC;
import static de.kai_morich.simple_bluetooth_terminal.MainActivity.sMediaProjection;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableStringBuilder;
import android.text.method.ScrollingMovementMethod;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

public class TerminalFragment extends Fragment implements ServiceConnection, SerialListener {

    private enum Connected { False, Pending, True }

    private String deviceAddress;
    private SerialService service;

    private TextView receiveText;
    private TextView sendText;
    private TextUtil.HexWatcher hexWatcher;

    private Connected connected = Connected.False;
    private boolean initialStart = true;
    private boolean hexEnabled = false;
    private boolean invertEnabled = false;
    public static boolean pictureEnabled = false;
    private boolean pendingNewline = false;
    private String newline = TextUtil.newline_crlf;
    /*
     * Lifecycle
     */

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);
        setRetainInstance(true);
        deviceAddress = getArguments().getString("device");
    }

    @Override
    public void onDestroy() {
        if (connected != Connected.False)
            disconnect();
        getActivity().stopService(new Intent(getActivity(), SerialService.class));
        super.onDestroy();
    }

    @Override
    public void onStart() {
        super.onStart();
        mSC = new ScreenCapturer(getActivity(), sMediaProjection, "");
        mSC.startProjection();
        if(service != null)
            service.attach(this);
        else
            getActivity().startService(new Intent(getActivity(), SerialService.class)); // prevents service destroy on unbind from recreated activity caused by orientation change
    }

    @Override
    public void onStop() {
        if(service != null && !getActivity().isChangingConfigurations())
            service.detach();
        super.onStop();
    }

    @SuppressWarnings("deprecation") // onAttach(context) was added with API 23. onAttach(activity) works for all API versions
    @Override
    public void onAttach(@NonNull Activity activity) {
        super.onAttach(activity);
        getActivity().bindService(new Intent(getActivity(), SerialService.class), this, Context.BIND_AUTO_CREATE);
    }

    @Override
    public void onDetach() {
        try { getActivity().unbindService(this); } catch(Exception ignored) {}
        super.onDetach();
    }

    @Override
    public void onResume() {
        super.onResume();
        if(initialStart && service != null) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceConnected(ComponentName name, IBinder binder) {
        service = ((SerialService.SerialBinder) binder).getService();
        service.attach(this);
        if(initialStart && isResumed()) {
            initialStart = false;
            getActivity().runOnUiThread(this::connect);
        }
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        service = null;
    }

    /*
     * UI
     */
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_terminal, container, false);
        receiveText = view.findViewById(R.id.receive_text);                          // TextView performance decreases with number of spans
        receiveText.setTextColor(getResources().getColor(R.color.colorRecieveText)); // set as default color to reduce number of spans
        receiveText.setMovementMethod(ScrollingMovementMethod.getInstance());

        sendText = view.findViewById(R.id.send_text);
        hexWatcher = new TextUtil.HexWatcher(sendText);
        hexWatcher.enable(hexEnabled);
        sendText.addTextChangedListener(hexWatcher);
        sendText.setHint(hexEnabled ? "HEX mode" : "");

        View sendBtn = view.findViewById(R.id.send_btn);
        sendBtn.setOnClickListener(v -> send(sendText.getText().toString()));
        return view;
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, MenuInflater inflater) {
        inflater.inflate(R.menu.menu_terminal, menu);
        menu.findItem(R.id.hex).setChecked(hexEnabled);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.clear) {
            receiveText.setText("");
            return true;
        } else if (id == R.id.newline) {
            String[] newlineNames = getResources().getStringArray(R.array.newline_names);
            String[] newlineValues = getResources().getStringArray(R.array.newline_values);
            int pos = java.util.Arrays.asList(newlineValues).indexOf(newline);
            AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
            builder.setTitle("Newline");
            builder.setSingleChoiceItems(newlineNames, pos, (dialog, item1) -> {
                newline = newlineValues[item1];
                dialog.dismiss();
            });
            builder.create().show();
            return true;
        } else if (id == R.id.hex) {
            hexEnabled = !hexEnabled;
            sendText.setText("");
            hexWatcher.enable(hexEnabled);
            sendText.setHint(hexEnabled ? "HEX mode" : "");
            item.setChecked(hexEnabled);
            return true;
        } else if (id == R.id.twitter) {
            invertEnabled = !invertEnabled;
            return true;
        }
          else if (id == R.id.picture) {
            if(connected != Connected.True) {
                Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            } else {



                final int interval = 2000;
                final Handler runAtInterval = new Handler();
                Runnable screenshotAndSend = new Runnable() {
                    @Override
                    public void run() {
                        try{


        pictureEnabled = true;
        // split into 4 segments
        byte bytePic[] = new byte[1024];
        mSC.takeScreenshot();
        encodePic(bytePic, mSC.screenshotBitmap); // tests
        final int delayedTime = 150;
        // send 1/4 pic per 0.15 second
        new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    int i1 = 0;
                    byte parsedData[] = new byte[256+2]; // last 2 bytes for end line
                    for(int i2=0;i2<256;i2++){
                        parsedData[i2] = bytePic[i1*256+i2];
                    }
                    parsedData[256] = '\r';
                    parsedData[257] = '\n';
                    service.write(parsedData);
                    receiveText.append("sending pic" + String.valueOf(i1) + "\n");

                } catch (Exception e) {
                    onSerialIoError(e);
                }
            }
        }, 0);

        new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    int i1 = 1;
                    byte parsedData[] = new byte[256+2]; // last 2 bytes for end line
                    for(int i2=0;i2<256;i2++){
                        parsedData[i2] = bytePic[i1*256+i2];
                    }
                    parsedData[256] = '\r';
                    parsedData[257] = '\n';
                    service.write(parsedData);
                    receiveText.append("sending pic" + String.valueOf(i1) + "\n");

                } catch (Exception e) {
                    onSerialIoError(e);
                }
            }
        }, delayedTime*1);

        new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    int i1 = 2;
                    byte parsedData[] = new byte[256+2]; // last 2 bytes for end line
                    for(int i2=0;i2<256;i2++){
                        parsedData[i2] = bytePic[i1*256+i2];
                    }
                    parsedData[256] = '\r';
                    parsedData[257] = '\n';
                    service.write(parsedData);
                    receiveText.append("sending pic" + String.valueOf(i1) + "\n");

                } catch (Exception e) {
                    onSerialIoError(e);
                }
            }
        }, delayedTime*2);

        new android.os.Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
            @Override
            public void run() {
                try {
                    int i1 = 3;
                    byte parsedData[] = new byte[256+2]; // last 2 bytes for end line
                    for(int i2=0;i2<256;i2++){
                        parsedData[i2] = bytePic[i1*256+i2];
                    }
                    parsedData[256] = '\r';
                    parsedData[257] = '\n';
                    service.write(parsedData);
                    receiveText.append("sending pic" + String.valueOf(i1) + "\n");

                } catch (Exception e) {
                    onSerialIoError(e);
                }
            }
        }, delayedTime*3);
        pictureEnabled = false;


                        }
                        catch(Exception e) {
                            onSerialIoError(e);
                        }
                        finally{
                            runAtInterval.postDelayed(this, interval);
                        }
                    }
                };
                runAtInterval.post(screenshotAndSend);
            }
            return true;
        } else {
            return super.onOptionsItemSelected(item);
        }
    }



    private void encodePic(byte[] bytePic, Bitmap screenshot) { // encode ARGB8888 bitmap into 1024 bytes
        int[] pixels = new int[128*64];
        Bitmap bar = Bitmap.createBitmap(screenshot, 0, 0,  screenshot.getWidth()/3, screenshot.getWidth()/3/8);
        Bitmap barResized = Bitmap.createScaledBitmap(bar,128,16,true);

//        int iconWidth = screenshot.getWidth()/5;
//        int scrollbarWidth = screenshot.getWidth()/18;
//        int tweetWidth = screenshot.getWidth() - iconWidth - scrollbarWidth;
        Bitmap content = Bitmap.createBitmap(screenshot, 0, screenshot.getHeight()/4,  screenshot.getWidth(), screenshot.getWidth()/8*3);
        Bitmap contentResized = Bitmap.createScaledBitmap(content,128,48,true);

        Bitmap result = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888);
        Canvas comboImage = new Canvas(result);
        comboImage.drawBitmap(barResized, 0, 0, null);
        comboImage.drawBitmap(contentResized, 0, 16, null);
        result.getPixels(pixels, 0, 128, 0, 0, 128,64);
        for(int i1=0;i1<1024;i1++){
            bytePic[i1] = (byte) 0;
            for(int i2=0;i2<8;i2++){
                // decode ARGB
                int alpha = (pixels[i1*8+i2] >> 24) & 0xFF;
                int red = (pixels[i1*8+i2] >> 16) & 0xFF;
                int green = (pixels[i1*8+i2] >> 8) & 0xFF;
                int blue = pixels[i1*8+i2] & 0xFF;
                if(invertEnabled){
                    if((red+green+blue)/3 < 0xF0)
                        bytePic[i1] |= (1 << (7-i2));
                }
                else{
                    if((red+green+blue)/3 > 0xF0)
                        bytePic[i1] |= (1 << (7-i2));
                }

            }
        }
        bar.recycle();
        barResized.recycle();
        content.recycle();
        contentResized.recycle();
        result.recycle();
    }

    /*
     * Serial + UI
     */
    private void connect() {
        try {
            BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            BluetoothDevice device = bluetoothAdapter.getRemoteDevice(deviceAddress);
            status("connecting...");
            connected = Connected.Pending;
            SerialSocket socket = new SerialSocket(getActivity().getApplicationContext(), device);
            service.connect(socket);
        } catch (Exception e) {
            onSerialConnectError(e);
        }
    }

    private void disconnect() {
        connected = Connected.False;
        service.disconnect();
    }

    private void send(String str) {
        if(connected != Connected.True) {
            Toast.makeText(getActivity(), "not connected", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            String msg;
            byte[] data;
            if(hexEnabled) {
                StringBuilder sb = new StringBuilder();
                TextUtil.toHexString(sb, TextUtil.fromHexString(str));
                TextUtil.toHexString(sb, newline.getBytes());
                msg = sb.toString();
                data = TextUtil.fromHexString(msg);
            } else {
                msg = str;
                data = (str + newline).getBytes();
            }
            SpannableStringBuilder spn = new SpannableStringBuilder(msg + '\n');
            spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorSendText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            receiveText.append(spn);
            service.write(data);
        } catch (Exception e) {
            onSerialIoError(e);
        }
    }

    private void receive(byte[] data) {
        if(hexEnabled) {
            receiveText.append(TextUtil.toHexString(data) + '\n');
        } else {
            String msg = new String(data);
            if(newline.equals(TextUtil.newline_crlf) && msg.length() > 0) {
                // don't show CR as ^M if directly before LF
                msg = msg.replace(TextUtil.newline_crlf, TextUtil.newline_lf);
                // special handling if CR and LF come in separate fragments
                if (pendingNewline && msg.charAt(0) == '\n') {
                    Editable edt = receiveText.getEditableText();
                    if (edt != null && edt.length() > 1)
                        edt.replace(edt.length() - 2, edt.length(), "");
                }
                pendingNewline = msg.charAt(msg.length() - 1) == '\r';
            }
            receiveText.append(TextUtil.toCaretString(msg, newline.length() != 0));
        }
    }

    private void status(String str) {
        SpannableStringBuilder spn = new SpannableStringBuilder(str + '\n');
        spn.setSpan(new ForegroundColorSpan(getResources().getColor(R.color.colorStatusText)), 0, spn.length(), Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        receiveText.append(spn);
    }

    /*
     * SerialListener
     */
    @Override
    public void onSerialConnect() {
        status("connected");
        connected = Connected.True;
    }

    @Override
    public void onSerialConnectError(Exception e) {
        status("connection failed: " + e.getMessage());
        disconnect();
    }

    @Override
    public void onSerialRead(byte[] data) {
        receive(data);
    }

    @Override
    public void onSerialIoError(Exception e) {
        status("connection lost: " + e.getMessage());
        disconnect();
    }

}
