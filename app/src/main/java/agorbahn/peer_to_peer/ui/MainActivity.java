package agorbahn.peer_to_peer.ui;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.PreferenceManager;
import android.provider.MediaStore;
import android.support.v4.app.ActivityCompat;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.LinearLayoutManager;
import android.support.v7.widget.RecyclerView;
import android.util.Base64;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;

import agorbahn.peer_to_peer.Constants;
import agorbahn.peer_to_peer.R;
import agorbahn.peer_to_peer.adapters.BluetoothListDialogs;
import agorbahn.peer_to_peer.adapters.ChatController;
import agorbahn.peer_to_peer.adapters.MessageAdapter;
import agorbahn.peer_to_peer.command.Command;
import agorbahn.peer_to_peer.helper.AESHelper;
import agorbahn.peer_to_peer.helper.FontManager;
import agorbahn.peer_to_peer.models.ChatMessage;
import butterknife.Bind;
import butterknife.ButterKnife;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothDevice mDevice;
    private Handler mHandler;
    private ChatController chatController;
    private MessageAdapter mChatAdapter;
    private ArrayList<ChatMessage> mChatMessages;
    @Bind(R.id.list_of_messages) RecyclerView mListView;
    @Bind(R.id.send) Button mSend;
    @Bind(R.id.input) EditText mInput;
    private AESHelper mEncryption;
    private String mUser;
    private Command mCommand;
    private LinearLayoutManager mLinearLayoutManager;
    private boolean show;
    private SharedPreferences mSharedPreferences;
    private SharedPreferences.Editor mEditor;
    private static final int REQUEST_IMAGE_CAPTURE = 111;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        ButterKnife.bind(this);
        show = false;
        mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (mBluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth is not available!", Toast.LENGTH_SHORT).show();
            finish();
        }

        mCommand = new Command();

        // only way to work within android v. 6
        ActivityCompat.requestPermissions(this,
                new String[]{Manifest.permission.ACCESS_COARSE_LOCATION},1001);

        mEncryption = new AESHelper();
        mChatMessages = new ArrayList<ChatMessage>();
        mChatAdapter = new MessageAdapter(this, mChatMessages);
        mListView.setAdapter(mChatAdapter);
        mLinearLayoutManager = new LinearLayoutManager(this);
        mListView.setLayoutManager(mLinearLayoutManager);
        mSharedPreferences = PreferenceManager.getDefaultSharedPreferences(this);
        mEditor = mSharedPreferences.edit();

        mHandler = new Handler(new Handler.Callback() {
            @Override
            public boolean handleMessage(Message msg) {
                switch (msg.what) {
                    case Constants.MESSAGE_STATE_CHANGE:
                        switch (msg.arg1) {
                            case Constants.STATE_CONNECTED:
                                setTitle(mDevice.getName());
                                break;
                            case Constants.STATE_CONNECTING:
                                setTitle("Connecting...");
                                break;
                            case Constants.STATE_LISTEN:
                            case Constants.STATE_NONE:
                                setTitle("Not connected");
                                break;
                        }
                        break;
                    case Constants.MESSAGE_WRITE:
                        byte[] writeBuf = (byte[]) msg.obj;
                        String writeMessage = new String(writeBuf);
                        jsonMessage(writeMessage, true);
                        break;
                    case Constants.MESSAGE_READ:
                        byte[] readBuf = (byte[]) msg.obj;
                        String readMessage = new String(readBuf, 0, msg.arg1);
                        jsonMessage(readMessage, false);
                        AESHelper encryption = new AESHelper();
                        try {
                            JSONObject messageJSON = new JSONObject(readMessage);
                            String key = encryption.decrypt(Constants.ENCRYPT_SEED, messageJSON.get("key").toString());
                            String message2 =  encryption.decrypt(key, messageJSON.get("message").toString());
                            addToSharedPreferences(mCommand.type(message2, MainActivity.this));
                        } catch (JSONException e) {
                            e.printStackTrace();
                        } catch (Exception e) {
                            e.printStackTrace();
                        }


                        break;
                    case Constants.MESSAGE_DEVICE_OBJECT:
                        mDevice = msg.getData().getParcelable(Constants.DEVICE_OBJECT);
                        Toast.makeText(getApplicationContext(), "Connected to " + mDevice.getName(),
                                Toast.LENGTH_SHORT).show();
                        break;
                    case Constants.MESSAGE_TOAST:
                        Toast.makeText(getApplicationContext(), msg.getData().getString("toast"),
                                Toast.LENGTH_SHORT).show();
                        break;
                    case Constants.MESSAGE_LOST:
                        ChatController.sleep(500);
                        Toast.makeText(getApplicationContext(), "Reconnected", Toast.LENGTH_SHORT).show();
                        chatController.connect(mDevice);
                        break;
                }
                return false;
            }
        });

        mSend.setTypeface(FontManager.getTypeface(this,"fontawesome-webfont.ttf"));

        mSend.setOnClickListener(this);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();
        if (id == R.id.action_bluetooth) {
            bluetoothSearch();
            return true;
        }

        if (id == R.id.action_photo) {
            sendPhoto();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.menu, menu);

        super.onCreateOptionsMenu(menu);
        return true;
    }

    private void bluetoothSearch() {
        BluetoothListDialogs display = new BluetoothListDialogs(mBluetoothAdapter, chatController);
        display.show(this);
        chatController = display.getChatController();
    }

    @Override
    public void onStart() {
        super.onStart();
        if (!mBluetoothAdapter.isEnabled()) {
            Intent enableIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
            startActivityForResult(enableIntent, Constants.REQUEST_ENABLE_BLUETOOTH);
        } else {
            chatController = new ChatController(this, mHandler);
        }
    }

    @Override
    public void onResume() {
        super.onResume();

        if (chatController != null) {
            if (chatController.getState() == Constants.STATE_NONE) chatController.start();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (chatController != null)
            chatController.stop();
    }

    @Override
    public void onClick(View v) {
        if (mInput.getText().toString().equals("")) {
            Toast.makeText(this, "Please input some texts", Toast.LENGTH_SHORT).show();
        } else {
            sendMessage(mInput.getText().toString());
            mInput.setText("");
        }
    }

    private void sendMessage(String message) {
        if (chatController.getState() != Constants.STATE_CONNECTED) {
            Toast.makeText(this, "Connection was lost!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (message.length() > 0) {
            byte[] send = makeJSON(message, "").getBytes();
            chatController.write(send);
        }
    }

    private void sendPhoto() {
        Intent takePictureIntent = new Intent(MediaStore.ACTION_IMAGE_CAPTURE);
        if (takePictureIntent.resolveActivity(this.getPackageManager()) != null) {

            if (chatController.getState() != Constants.STATE_CONNECTED) {
                Toast.makeText(this, "Connection was lost!", Toast.LENGTH_SHORT).show();
                return;
            } else {
                startActivityForResult(takePictureIntent, REQUEST_IMAGE_CAPTURE);
            }
        }
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_IMAGE_CAPTURE && resultCode == this.RESULT_OK) {
            Bundle extras = data.getExtras();
            Bitmap imageBitmap = (Bitmap) extras.get("data");
            String image = encodeBitmap(imageBitmap);

            if (chatController.getState() != Constants.STATE_CONNECTED) {
                Toast.makeText(this, "Connection was lost!", Toast.LENGTH_SHORT).show();
                return;
            }

            if (image.length() > 0) {
                byte[] send = makeJSON("", image).getBytes();
                chatController.write(send);
            }
        }
    }

    public String encodeBitmap(Bitmap bitmap) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);
        String imageEncoded = Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT);
        return imageEncoded;
    }

    private String makeJSON(String message, String image) {
        JSONObject json = new JSONObject();
        String random = mEncryption.randomKey();
        try {
            message = mEncryption.encrypt(random, message);
            random = mEncryption.encrypt(Constants.ENCRYPT_SEED, random);
        } catch (Exception e) {
            e.printStackTrace();
        }
        try {
            json.put("key", random);
            json.put("message", message);
            json.put("from", mBluetoothAdapter.getName());
            json.put("image", image);

        } catch (JSONException e) {
            e.printStackTrace();
        }

        return json.toString();
    }

    private void jsonMessage(String jsonData, boolean write) {
        try {
            JSONObject messageJSON = new JSONObject(jsonData);
            ChatMessage message = new ChatMessage(messageJSON.get("message").toString(), messageJSON.get("from").toString(), messageJSON.get("key").toString(), write, messageJSON.get("image").toString());

            mChatMessages.add(message);
            mChatAdapter.notifyDataSetChanged();

        } catch (JSONException e) {
            e.printStackTrace();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void addToSharedPreferences(String type) {
        mEditor.putString("type", type).apply();
    }
}