/*
 * Copyright 2015 MongoDB, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.mongodb

import com.mongodb.operation.BatchCursor
import com.mongodb.operation.DistinctOperation
import org.bson.BsonDocument
import org.bson.BsonInt32
import org.bson.Document
import org.bson.codecs.BsonValueCodecProvider
import org.bson.codecs.DocumentCodec
import org.bson.codecs.DocumentCodecProvider
import org.bson.codecs.ValueCodecProvider
import org.bson.codecs.configuration.CodecConfigurationException
import spock.lang.Specification

import static com.mongodb.CustomMatchers.isTheSameAs
import static com.mongodb.ReadPreference.secondary
import static java.util.concurrent.TimeUnit.MILLISECONDS
import static org.bson.codecs.configuration.CodecRegistries.fromProviders
import static spock.util.matcher.HamcrestSupport.expect

class DistinctIterableSpecification extends Specification {

    def namespace = new MongoNamespace('db', 'coll')
    def codecRegistry = fromProviders([new ValueCodecProvider(), new DocumentCodecProvider(), new BsonValueCodecProvider()])
    def readPreference = secondary()

    def 'should build the expected DistinctOperation'() {
        given:
        def executor = new TestOperationExecutor([null, null]);
        def distinctIterable = new DistinctIterableImpl(namespace, Document, Document, codecRegistry, readPreference, executor, 'field')

        when: 'default input should be as expected'
        distinctIterable.iterator()

        def operation = executor.getReadOperation() as DistinctOperation<Document>
        def readPreference = executor.getReadPreference()

        then:
        expect operation, isTheSameAs(new DistinctOperation<Document>(namespace, 'field', new DocumentCodec()));
        readPreference == secondary()

        when: 'overriding initial options'
        distinctIterable.filter(new Document('field', 1)).maxTime(999, MILLISECONDS).batchSize(99).iterator()

        operation = executor.getReadOperation() as DistinctOperation<Document>

        then: 'should use the overrides'
        expect operation, isTheSameAs(new DistinctOperation<Document>(namespace, 'field', new DocumentCodec())
                .filter(new BsonDocument('field', new BsonInt32(1)))
                .maxTime(999, MILLISECONDS))
    }

    def 'should handle exceptions correctly'() {
        given:
        def codecRegistry = fromProviders([new ValueCodecProvider(), new BsonValueCodecProvider()])
        def executor = new TestOperationExecutor([new MongoException('failure')])
        def distinctIterable = new DistinctIterableImpl(namespace, Document, BsonDocument, codecRegistry, readPreference, executor, 'field')

        when: 'The operation fails with an exception'
        distinctIterable.iterator()

        then: 'the future should handle the exception'
        thrown(MongoException)

        when: 'a codec is missing'
        distinctIterable.filter(new Document('field', 1)).iterator()
        then:
        thrown(CodecConfigurationException)
    }

    def 'should follow the MongoIterable interface as expected'() {
        given:
        def cannedResults = [new Document('_id', 1), new Document('_id', 2), new Document('_id', 3)]
        def cursor = {
            Stub(BatchCursor) {
                def count = 0
                def results;
                def getResult = {
                    count++
                    results = count == 1 ? cannedResults : null
                    results
                }
                next() >> {
                    getResult()
                }
                hasNext() >> {
                    count == 0
                }

            }
        }
        def executor = new TestOperationExecutor([cursor(), cursor(), cursor(), cursor()]);
        def mongoIterable = new DistinctIterableImpl(namespace, Document, Document, codecRegistry, readPreference, executor, 'field')

        when:
        def results = mongoIterable.first()

        then:
        results == cannedResults[0]

        when:
        def count = 0
        mongoIterable.forEach(new Block<Document>() {
            @Override
            void apply(Document document) {
                count++
            }
        })

        then:
        count == 3

        when:
        def target = []
        mongoIterable.into(target)

        then:
        target == cannedResults

        when:
        target = []
        mongoIterable.map(new Function<Document, Integer>() {
            @Override
            Integer apply(Document document) {
                document.getInteger('_id')
            }
        }).into(target)

        then:
        target == [1, 2, 3]
    }
}
