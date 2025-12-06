package com.cortalabs.osrs.analytics.beans;

import lombok.Value;

@Value
public class SnapshotData
{
    SnapshotSkills skills;
    SnapshotBosses bosses;
    SnapshotActivities activities;
    SnapshotComputed computed;
}
