package com.example.models;

public class User {
    private long id;
    private String name;
    private String phone;
    private String photo;

    public User(long id, String name, String phone, String photo) {
        this.id = id;
        this.name = name;
        this.phone = phone;
        this.photo = photo;
    }

    public long getId() { return id; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getPhoto() { return photo; }

    public void setId(long id) { this.id = id; }
    public void setName(String name) { this.name = name; }
    public void setPhone(String phone) { this.phone = phone; }
    public void setPhoto(String photo) { this.photo = photo; }
}
