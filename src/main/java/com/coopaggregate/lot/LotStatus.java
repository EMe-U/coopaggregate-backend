package com.coopaggregate.lot;

public enum LotStatus {
    // Lots from before fixed-size lots: they keep their size and get no new kg.
    OPEN,
    CLOSED,
    SOLD,
    SHARED,
    // A fixed-size lot being filled (at most one per grade), and one that holds its capacity.
    FILLING,
    FULL,
    // A full lot left with nothing because its deliveries were reversed or corrected before any kg were sold. It
    // cannot be filled, sold from, given a loss or shared.
    EMPTIED
}
