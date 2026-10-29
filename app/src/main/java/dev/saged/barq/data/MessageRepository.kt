// Removed in 1.2.0: its work moved to ConversationRepository, IncomingRepository
// and OutgoingRepository.
//
// Uploading the project zip from the phone only adds and overwrites files; it never
// deletes. This empty file overwrites the old MessageRepository.kt so the old code
// stops being compiled. It is safe to delete this file from the repository.
package dev.saged.barq.data
