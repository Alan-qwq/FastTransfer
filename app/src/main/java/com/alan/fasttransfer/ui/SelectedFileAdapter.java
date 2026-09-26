package com.alan.fasttransfer.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.alan.fasttransfer.R;
import com.alan.fasttransfer.core.transfer.SendItem;
import com.alan.fasttransfer.core.util.Formatters;

import java.util.ArrayList;
import java.util.List;

/**
 * 已选待发送文件列表。
 */
public class SelectedFileAdapter extends RecyclerView.Adapter<SelectedFileAdapter.Holder> {

    public interface OnRemove {
        void onRemove(int position);
    }

    private final List<SendItem> items = new ArrayList<>();
    private final OnRemove listener;

    public SelectedFileAdapter(OnRemove listener) {
        this.listener = listener;
    }

    public void submit(List<SendItem> newItems) {
        items.clear();
        if (newItems != null) {
            items.addAll(newItems);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_selected_file, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        SendItem item = items.get(position);
        holder.name.setText(item.name);
        holder.size.setText(item.size >= 0
                ? Formatters.size(item.size)
                : holder.itemView.getContext().getString(R.string.incoming_text_preview));
        final int index = position;
        holder.remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (listener != null) {
                    listener.onRemove(index);
                }
            }
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class Holder extends RecyclerView.ViewHolder {
        final TextView name;
        final TextView size;
        final ImageView remove;

        Holder(View itemView) {
            super(itemView);
            name = itemView.findViewById(R.id.tv_file_name);
            size = itemView.findViewById(R.id.tv_file_size);
            remove = itemView.findViewById(R.id.btn_remove_file);
        }
    }
}
