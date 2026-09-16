package org.nhrc.grants.error

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.time.OffsetDateTime

@RestControllerAdvice
class ApiExceptionHandler {
 @ExceptionHandler(IllegalArgumentException::class)
 fun badRequest(ex:IllegalArgumentException)=ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("timestamp" to OffsetDateTime.now().toString(),"status" to 400,"error" to "Bad Request","message" to (ex.message?:"Invalid request")))
 @ExceptionHandler(MethodArgumentNotValidException::class)
 fun validation(ex:MethodArgumentNotValidException)=ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("timestamp" to OffsetDateTime.now().toString(),"status" to 400,"error" to "Validation failed","fields" to ex.bindingResult.fieldErrors.associate{it.field to (it.defaultMessage?:"Invalid value")}))
}
