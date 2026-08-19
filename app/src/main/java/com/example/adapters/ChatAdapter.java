package com.example.adapters;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.R;
import com.example.models.User;

import java.util.ArrayList;
import java.util.List;

public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ViewHolder> {

    private List<User> userList;
    private OnChatClickListener clickListener;
    private OnChatLongClickListener longClickListener;

    public interface OnChatClickListener {
        void onChatClick(User user);
    }

    public interface OnChatLongClickListener {
        void onChatLongClick(User user);
    }

    public ChatAdapter(List<User> userList, OnChatClickListener clickListener) {
        this.userList = (userList != null) ? new ArrayList<>(userList) : new ArrayList<>();
        this.clickListener = clickListener;
    }

    public ChatAdapter(List<User> userList, OnChatClickListener clickListener, OnChatLongClickListener longClickListener) {
        this.userList = (userList != null) ? new ArrayList<>(userList) : new ArrayList<>();
        this.clickListener = clickListener;
        this.longClickListener = longClickListener;
    }

    public void setOnChatLongClickListener(OnChatLongClickListener longClickListener) {
        this.longClickListener = longClickListener;
    }

    public void updateList(List<User> newUsers) {
        this.userList = (newUsers != null) ? new ArrayList<>(newUsers) : new ArrayList<>();
        notifyDataSetChanged();
    }

    public void updateUserList(List<User> newUsers) {
        updateList(newUsers);
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_chat, parent, false);
        return new ViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        User user = userList.get(position);

        if (user != null) {
            String name = (user.getName() != null && !user.getName().trim().isEmpty()) ? user.getName() : user.getPhone();
            holder.tvName.setText(name);

            String phoneLabel = (user.getPhone() != null && !user.getPhone().trim().isEmpty()) ? user.getPhone() : "";
            holder.tvLastMsg.setText("Tap to open SMS / Data conversation (" + phoneLabel + ")");
            holder.tvTime.setText("Now");

            holder.itemView.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (clickListener != null) {
                        clickListener.onChatClick(user);
                    }
                }
            });

            holder.itemView.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(View v) {
                    if (longClickListener != null) {
                        longClickListener.onChatLongClick(user);
                        return true;
                    }
                    return false;
                }
            });
        }
    }

    @Override
    public int getItemCount() {
        return (userList != null) ? userList.size() : 0;
    }

    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tvName, tvLastMsg, tvTime;

        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tvChatName);
            tvLastMsg = itemView.findViewById(R.id.tvLastMessage);
            tvTime = itemView.findViewById(R.id.tvChatTimestamp);
        }
    }
}