package com.alan.fasttransfer.ui;

import android.graphics.Bitmap;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.Nullable;

import com.alan.fasttransfer.R;

/**
 * 全屏二维码：方便对方在较远处扫码。
 */
public class QrDisplayActivity extends BaseActivity {

    public static final String EXTRA_CONTENT = "extra_content";
    public static final String EXTRA_ADDRESS = "extra_address";

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_qr);

        ImageView image = findViewById(R.id.iv_qr_large);
        TextView address = findViewById(R.id.tv_qr_large_address);

        String content = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_CONTENT);
        String addressText = getIntent() == null ? null : getIntent().getStringExtra(EXTRA_ADDRESS);
        address.setText(addressText == null ? "" : addressText);

        Bitmap bitmap = QrUtils.renderForView(content, image);
        if (bitmap != null) {
            image.setImageBitmap(bitmap);
        } else {
            image.setImageResource(R.drawable.ic_qr_large);
        }

        findViewById(R.id.btn_qr_large_close).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                finish();
            }
        });
    }
}
