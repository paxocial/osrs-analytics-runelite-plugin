package com.cortalabs.osrs.analytics.panel;

import lombok.Value;
import net.runelite.client.hiscore.HiscoreSkill;

@Value
public class RowPair
{
    HiscoreSkill skill;
    TableRow row;
}
