package com.example.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.models.CallLogItem;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public class CallsAdapter extends RecyclerView.Adapter<CallsAdapter.ViewHolder> {

    public interface OnCallItemClickListener {
        void onCallClick(CallLogItem item);
    }

    public interface OnCallItemLongClickListener {
        void onCallLongClick(CallLogItem item);
    }

    private List<CallLogItem> callList;
    private OnCallItemClickListener listener;
    private OnCallItemLongClickListener longClickListener;

    public CallsAdapter(List<CallLogItem> callList) {
        this.callList = (callList != null) ? callList : new ArrayList<>();
    }

    public CallsAdapter(List<CallLogItem> callList, OnCallItemClickListener listener) {
        this.callList = (callList != null) ? callList : new ArrayList<>();
        this.listener = listener;
    }

    public CallsAdapter(List<CallLogItem> callList, OnCallItemClickListener listener, OnCallItemLongClickListener longClickListener) {
        this.callList = (callList != null) ? callList : new ArrayList<>();
        this.listener = listener;
        this.longClickListener = longClickListener;
    }

    public void setOnCallItemClickListener(OnCallItemClickListener listener) {
        this.listener = listener;
    }

    public void setOnCallItemLongClickListener(OnCallItemLongClickListener longClickListener) {
        this.longClickListener = longClickListener;
    }

    public void updateList(List<CallLogItem> newCalls) {
        this.callList = (newCalls != null) ? new ArrayList<>(newCalls) : new ArrayList<>();
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_call, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        CallLogItem item = callList.get(position);
        if (item == null) return;

        String typeStr = item.getType();
        String displayType = typeStr;
        int colorRes = 0xFF4CAF50;

        if ("OUTGOING".equalsIgnoreCase(typeStr) || "DIALLED".equalsIgnoreCase(typeStr)) {
            displayType = "Dialled";
            colorRes = 0xFF4CAF50;
        } else if ("INCOMING".equalsIgnoreCase(typeStr) || "RECEIVED".equalsIgnoreCase(typeStr)) {
            displayType = "Received";
            colorRes = 0xFF2196F3;
        } else if ("MISSED".equalsIgnoreCase(typeStr)) {
            displayType = "Missed";
            colorRes = 0xFFFF5252;
        } else if ("AUDIO_DATA".equalsIgnoreCase(typeStr)) {
            displayType = "Audio Data";
            colorRes = 0xFF00E5FF;
        }

        // Show Contact Name if saved; otherwise show raw number
        String mainTitle = item.hasContactName() ? item.getName() : item.getNumber();
        holder.tvNumber.setText(mainTitle + " (" + displayType + ")");

        if (holder.imgCallType != null) {
            holder.imgCallType.setColorFilter(colorRes);
        }

        SimpleDateFormat sdf = new SimpleDateFormat("MMM dd, hh:mm a", Locale.getDefault());
        holder.tvTime.setText(sdf.format(new Date(item.getTimestamp())));
        holder.tvDuration.setText(item.getDuration() > 0 ? item.getDuration() + "s" : displayType);

        holder.itemView.setOnClickListener(v -> {
            if (listener != null) {
                listener.onCallClick(item);
            }
        });

        holder.itemView.setOnLongClickListener(v -> {
            if (longClickListener != null) {
                longClickListener.onCallLongClick(item);
                return true;
            }
            return false;
        });
    }

    @Override
    public int getItemCount() {
        return (callList != null) ? callList.size() : 0;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvNumber, tvTime, tvDuration;
        ImageView imgCallType;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvNumber = itemView.findViewById(R.id.tvCallNumber);
            tvTime = itemView.findViewById(R.id.tvCallTime);
            tvDuration = itemView.findViewById(R.id.tvCallDuration);
            imgCallType = itemView.findViewById(R.id.imgCallType);
        }
    }
}