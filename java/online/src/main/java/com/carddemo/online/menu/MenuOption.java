package com.carddemo.online.menu;

public record MenuOption(int num, String name, String program, String userType, String text) {
    public MenuOption(int num, String name, String program, String userType) {
        this(num, name, program, userType, String.format("%02d. %s", num, name));
    }
}
