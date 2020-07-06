package com.marcuschiu.meet.android;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

public class MainActivity extends Activity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        findViewById(R.id.connectBtn).setOnClickListener((arg) -> {
            Intent myIntent = new Intent(this, CallActivity.class);
            startActivity(myIntent);
        });
    }
}