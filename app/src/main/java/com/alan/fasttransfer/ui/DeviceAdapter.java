package com.alan.fasttransfer.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.alan.fasttransfer.R;
import com.alan.fasttransfer.core.transfer.Peer;

import java.util.ArrayList;
import java.util.List;

/**
 * 附近设备列表。
 */
public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder> {

    public interface OnDeviceClick {
        void onDeviceClick(Peer peer);
    }

    private final List<Peer> items = new ArrayList<>();
    private final OnDeviceClick listener;

    public DeviceAdapter(OnDeviceClick listener) {
        this.listener = listener;
    }

    public void submit(List<Peer> peers) {
        items.clear();
        if (peers != null) {
            items.addAll(peers);
        }
        notifyDataSetChanged();
    }

    public int size() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        final Peer peer = items.get(position);
        holder.name.setText(peer.displayName());
        String subtitle = peer.subtitle();
        holder.subtitle.setText(subtitle);
        holder.icon.setImageResource(UiUtils.deviceIcon(peer.deviceType));
        holder.dot.setVisibility(peer.reachable ? View.VISIBLE : View.GONE);
        holder.itemView.setAlpha(peer.reachable ? 1f : 0.55f);

        View.OnClickListener click = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onDeviceClick(peer);
                }
            }
        };
        holder.itemView.setOnClickListener(click);
        holder.action.setOnClickListener(click);
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView subtitle;
        final ImageView icon;
        final ImageView action;
        final View dot;

        Holder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.tv_device_name);
            subtitle = itemView.findViewById(R.id.tv_device_sub);
            icon = itemView.findViewById(R.id.iv_device_icon);
            action = itemView.findViewById(R.id.btn_device_action);
            dot = itemView.findViewById(R.id.view_online_dot);
        }
    }
}
