package com.example.server

import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.datetime

object User : Table("User") {
    val username = varchar("username", 32)
    val password = binary("password", 32)
    val email = varchar("email", 128)
    val surname = varchar("surname", 64)
    val city = varchar("city", 64)
    val description = varchar("description", 512)
    val premium_id = reference("premium_id", Subscription.id).nullable()

    override val primaryKey = PrimaryKey(username, name = "PK_User_username")
}

object Invitation : Table("Invitation") {
    val drawing_id = reference("drawing_id", Drawing.id)
    val guest_username = reference("guest_username", User.username)
}

object Subscription : Table("Subscription") {
    val id = integer("id").autoIncrement()
    val start = datetime("start")
    val end = datetime("end")

    override val primaryKey = PrimaryKey(id, name = "PK_Subscription_id")
}

object Drawing : Table("Drawing") {
    val id = integer("id").autoIncrement()
    val title = varchar("title", 64)
    val description = varchar("description", 512)
    val public = bool("public")
    val owner_username = reference("owner_username", User.username)

    override val primaryKey = PrimaryKey(id, name = "PK_Drawing_id")
}

object History : Table("History") {
    val id = integer("id").autoIncrement()
    val save_date = datetime("save_date")
    val data = blob("data")
    val drawing_id = reference("drawing_id", Drawing.id)

    override val primaryKey = PrimaryKey(id, name = "PK_History_id")
}

object Collaboration : Table("Collaboration") {
    val user_username = reference("user_username", User.username)
    val drawing_id = reference("drawing_id", Drawing.id)
}