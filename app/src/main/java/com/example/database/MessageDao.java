package com.example.database;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

@Dao
public interface MessageDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    long insertMessage(MessageEntity message);

    @Query("SELECT * FROM messages WHERE sender = :address OR recipient = :address ORDER BY timestamp ASC")
    List<MessageEntity> getMessagesForConversation(String address);

    @Query("SELECT * FROM messages WHERE sender = :address OR recipient = :address ORDER BY timestamp ASC")
    LiveData<List<MessageEntity>> getMessagesLiveDataForConversation(String address);

    @Query("SELECT * FROM messages GROUP BY CASE WHEN sender = 'me' THEN recipient ELSE sender END ORDER BY timestamp DESC")
    LiveData<List<MessageEntity>> getAllConversationsLiveData();

    @Query("UPDATE messages SET status = :status WHERE id = :id")
    void updateStatus(long id, String status);

    @Query("UPDATE messages SET is_read = 1 WHERE sender = :address")
    void markConversationAsRead(String address);

    @Query("DELETE FROM messages WHERE id = :id")
    void deleteMessageById(long id);

    @Query("DELETE FROM messages WHERE sender = :address OR recipient = :address")
    void deleteConversation(String address);
}