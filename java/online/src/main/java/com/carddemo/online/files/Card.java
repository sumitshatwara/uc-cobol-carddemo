package com.carddemo.online.files;

public record Card(String cardNum, String acctId, int cvvCode, String embossedName,
                   String expirationDate, String activeStatus) {
}
