package example.axon.issue

import org.axonframework.eventsourcing.eventstore.jpa.AggregateEventEntry
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication
import org.springframework.boot.persistence.autoconfigure.EntityScan

@SpringBootApplication
@EntityScan(basePackageClasses = [AggregateEventEntry::class])
class ReproducerApplication

fun main(args: Array<String>) {
    runApplication<ReproducerApplication>(*args)
}
