package com.carddemo.online.menu;

/** One entry of CDEMO-MENU-OPTIONS (copybook COMEN02Y). */
public record MenuOption(int num, String name, String program, String userType) {

    /** 'NN. <name>' as BUILD-MENU-OPTIONS strings it (trailing blanks of the 35-character name dropped for display). */
    @com.fasterxml.jackson.annotation.JsonProperty("text")
    public String text() {
        return com.carddemo.online.Pic.rtrim(String.format("%02d", num) + ". " + name);
    }
}
