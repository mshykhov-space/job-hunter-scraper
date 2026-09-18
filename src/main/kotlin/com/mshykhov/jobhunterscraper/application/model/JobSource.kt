package com.mshykhov.jobhunterscraper.application.model

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

enum class JobSource(
    @get:JsonValue val id: String,
) {
    DJINNI("djinni"),
    DOU("dou"),
    EUREMOTEJOBS("euremotejobs"),
    JUSTJOINIT("justjoinit"),
    LANDINGJOBS("landingjobs"),
    LINKEDIN("linkedin"),
    NOFLUFFJOBS("nofluffjobs"),
    WEB3CAREER("web3career"),
    ;

    companion object {
        @JvmStatic
        @JsonCreator
        fun from(value: String): JobSource = entries.first { it.id == value.lowercase() }
    }
}
