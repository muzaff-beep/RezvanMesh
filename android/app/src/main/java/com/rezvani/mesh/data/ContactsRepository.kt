// android/app/src/main/java/com/rezvani/mesh/data/ContactsRepository.kt

package com.rezvani.mesh.data

import android.content.Context
import java.io.File

class ContactsRepository(private val context: Context) {

    private val contactsFile: File
        get() = File(context.filesDir, "contacts.txt")

    fun loadContacts(): List<Contact> {
        if (!contactsFile.exists()) return emptyList()
        return contactsFile.readLines().mapNotNull { line ->
            val parts = line.split("|")
            if (parts.size == 2) Contact(parts[0], parts[1]) else null
        }
    }

    fun saveContacts(contacts: List<Contact>) {
        contactsFile.bufferedWriter().use { writer ->
            contacts.forEach { contact ->
                writer.write("${contact.name}|${contact.nodeIdHex}\n")
            }
        }
    }

    fun addContact(contact: Contact) {
        val current = loadContacts().toMutableList()
        if (current.none { it.nodeIdHex == contact.nodeIdHex }) {
            current.add(contact)
            saveContacts(current)
        }
    }

    fun deleteContact(nodeIdHex: String) {
        val current = loadContacts().toMutableList()
        current.removeAll { it.nodeIdHex == nodeIdHex }
        saveContacts(current)
    }
}