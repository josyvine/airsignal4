package com.example;

import android.Manifest;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.activities.ConversationActivity;
import com.example.fragments.CallsFragment;
import com.example.fragments.ChatFragment;
import com.example.fragments.ContactsFragment;
import com.example.fragments.SettingsFragment;
import com.example.fragments.TransferFragment;
import com.example.utils.AirLogger;
import com.example.utils.SmsRoleManager;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.navigation.NavigationBarView;

import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";
    private static final int PERMISSION_REQUEST_CODE = 200;
    private BottomNavigationView bottomNavigationView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Initialize AirLogger
        AirLogger.init(this);
        AirLogger.i(TAG, "MainActivity onCreate called");

        bottomNavigationView = findViewById(R.id.bottomNavigationView);

        checkAndRequestPermissions();
        checkDefaultRoles();

        handleIncomingRecipientIntent(getIntent());

        if (savedInstanceState == null) {
            ChatFragment chatFragment = new ChatFragment();
            if (getIntent() != null && getIntent().hasExtra("target_recipient")) {
                Bundle args = new Bundle();
                args.putString("target_recipient", getIntent().getStringExtra("target_recipient"));
                chatFragment.setArguments(args);
            }
            loadFragment(chatFragment);
        }

        bottomNavigationView.setOnItemSelectedListener(new NavigationBarView.OnItemSelectedListener() {
            @Override
            public boolean onNavigationItemSelected(@NonNull MenuItem item) {
                Fragment selectedFragment = null;
                int itemId = item.getItemId();

                if (itemId == R.id.nav_chats) {
                    selectedFragment = new ChatFragment();
                } else if (itemId == R.id.nav_calls) {
                    selectedFragment = new CallsFragment();
                } else if (itemId == R.id.nav_contacts) {
                    selectedFragment = new ContactsFragment();
                } else if (itemId == R.id.nav_transfer) {
                    selectedFragment = new TransferFragment();
                } else if (itemId == R.id.nav_settings) {
                    selectedFragment = new SettingsFragment();
                }

                if (selectedFragment != null) {
                    loadFragment(selectedFragment);
                    return true;
                }
                return false;
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        AirLogger.i(TAG, "MainActivity onNewIntent called");
        handleIncomingRecipientIntent(intent);
    }

    private void handleIncomingRecipientIntent(Intent intent) {
        if (intent != null && intent.hasExtra("target_recipient")) {
            String targetRecipient = intent.getStringExtra("target_recipient");
            if (targetRecipient != null && !targetRecipient.trim().isEmpty()) {
                AirLogger.i(TAG, "Navigating to ConversationActivity for recipient: " + targetRecipient);
                Intent conversationIntent = new Intent(this, ConversationActivity.class);
                conversationIntent.putExtra("target_recipient", targetRecipient);
                startActivity(conversationIntent);
            }
        }
    }

    private void checkDefaultRoles() {
        boolean isDialer = SmsRoleManager.isDefaultDialerApp(this);
        boolean isSms = SmsRoleManager.isDefaultSmsApp(this);
        boolean hasOverlay = SmsRoleManager.hasOverlayPermission(this);

        AirLogger.i(TAG, "Role & Overlay check -> isDefaultDialer=" + isDialer + ", isDefaultSms=" + isSms + ", hasOverlay=" + hasOverlay);

        if (!isDialer || !isSms || !hasOverlay) {
            AlertDialog.Builder builder = new AlertDialog.Builder(this)
                    .setTitle("AirSignal Setup & Permissions")
                    .setMessage("To receive incoming calls, SMS, and show calling screens over locked screen on Oppo/ColorOS devices, AirSignal requires Default Dialer, Default SMS, and 'Display over other apps' permissions.");

            if (!isDialer) {
                builder.setPositiveButton("Set Phone App", (dialog, which) -> {
                    SmsRoleManager.requestDefaultDialerRole(MainActivity.this);
                });
            } else if (!isSms) {
                builder.setPositiveButton("Set SMS App", (dialog, which) -> {
                    SmsRoleManager.requestDefaultSmsRole(MainActivity.this);
                });
            } else if (!hasOverlay) {
                builder.setPositiveButton("Grant Overlay Perm", (dialog, which) -> {
                    SmsRoleManager.requestOverlayPermission(MainActivity.this);
                });
            }

            builder.setNeutralButton("Settings", (dialog, which) -> {
                SmsRoleManager.openSystemDefaultAppsSettings(MainActivity.this);
            });

            builder.setNegativeButton("Later", null);
            builder.show();
        }
    }

    private void loadFragment(Fragment fragment) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragmentContainer, fragment)
                .commit();
    }

    private void checkAndRequestPermissions() {
        List<String> permissions = new ArrayList<>();
        permissions.add(Manifest.permission.SEND_SMS);
        permissions.add(Manifest.permission.RECEIVE_SMS);
        permissions.add(Manifest.permission.READ_SMS);
        permissions.add(Manifest.permission.CALL_PHONE);
        permissions.add(Manifest.permission.RECORD_AUDIO);
        permissions.add(Manifest.permission.READ_CONTACTS);
        permissions.add(Manifest.permission.READ_PHONE_STATE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS);
        }

        List<String> listPermissionsNeeded = new ArrayList<>();
        for (String p : permissions) {
            if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                listPermissionsNeeded.add(p);
            }
        }

        if (!listPermissionsNeeded.isEmpty()) {
            AirLogger.i(TAG, "Requesting permissions: " + listPermissionsNeeded.toString());
            ActivityCompat.requestPermissions(this, listPermissionsNeeded.toArray(new String[0]), PERMISSION_REQUEST_CODE);
        } else {
            AirLogger.i(TAG, "All permissions are already granted.");
        }
    }
}