package com.example.activities;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.MainActivity;

public class ComposeSmsActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        String action = intent != null ? intent.getAction() : null;

        String recipient = "";
        if (intent != null && intent.getData() != null) {
            Uri data = intent.getData();
            recipient = data.getSchemeSpecificPart();
        }

        if (recipient.isEmpty() && intent != null && intent.hasExtra(Intent.EXTRA_PHONE_NUMBER)) {
            recipient = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
        }

        Intent mainIntent = new Intent(this, MainActivity.class);
        if (!recipient.isEmpty()) {
            mainIntent.putExtra("target_recipient", recipient);
        }
        startActivity(mainIntent);
        finish();
    }
}
