package org.olcbox.app.vpn.service

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Test

class GeneralControlOperationTest {
    @Test fun physicalBodyJoinAndThrowingCloseRemainUnknownUntilSameOwnedRetry(): Unit = runBlocking {
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val events=java.util.Collections.synchronizedList(mutableListOf<String>())
        var failClose=true;var settled=0
        val clock={java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime())}
        val request=object:GeneralControlRequest{
            override fun open(originalEnd:Long):GeneralControlResponse = GeneralControlResponse(200,emptyMap(),object:java.io.InputStream(){
                override fun read():Int{entered.countDown();check(release.await(2,java.util.concurrent.TimeUnit.SECONDS));events+="body-done";return -1}
            })
            override fun close(){events+="close";release.countDown();if(failClose)throw java.io.IOException("controlled close")}
        }
        val operation=GeneralControlOperation({request},clock,clock()+5_000,4096,{true},{settled++},false)
        val response=operation.open();assertEquals(200,response.status)
        assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
        assertFalse(operation.closeKnown(clock()+500));assertEquals(0,settled)
        assertTrue(events.contains("body-done"))
        failClose=false
        assertTrue(operation.closeKnown(clock()+500));assertEquals(1,settled)
        assertTrue(operation.closeKnown(clock()+500));assertEquals("known retry must not release twice",1,settled)
    }
    @Test fun lateFactoryNeverExecutesHttpAndCannotBeForgottenBeforePhysicalJoin(): Unit = runBlocking {
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val clock={java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime())};var opened=0;var closed=0;var settled=0
        val operation=GeneralControlOperation({entered.countDown();check(release.await(2,java.util.concurrent.TimeUnit.SECONDS));
            object:GeneralControlRequest{
                override fun open(originalEnd:Long):GeneralControlResponse{opened++;error("late HTTP must not run")}
                override fun close(){closed++}
            }},clock,clock()+5_000,4096,{true},{settled++},false)
        val job=kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).launch{try{operation.open()}catch(_:Exception){}}
        try{
            assertTrue(entered.await(1,java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(operation.closeKnown(clock()+50));assertEquals(0,settled)
            release.countDown();job.join()
            assertTrue(operation.closeKnown(clock()+500));assertEquals(0,opened);assertTrue(closed>0)
        }finally{release.countDown();job.join()}
    }
    @Test fun verifiedSameConsumerCanCaptureButRetiredEntryCannot(): Unit = runBlocking {
        val f = AndroidGeneralServiceConsumerTest.Fixture()
        val options = controlOptions(f)
        assertEquals(GeneralServiceResult.TransportVerified, f.consumer.handoff(f.binding, options, f.ops))
        try {
            val capture = f.consumer.captureControl({ f.current }, { action -> if (!f.current) false else { action(); true } })
            assertNotNull("real verified Consumer has no owned control capture", capture)
            assertTrue(capture!!.current())
            val pending = capture.open("https://rga.apollot.ru/updates/public/v1/manifest.json",false)!!
            assertNull(capture.open("https://other.example/updates/public/v1/manifest.json",false))
            f.consumer.retire()
            assertFalse(capture.current())
            assertTrue(f.consumer.stopCaptured(f.consumer.retire()!!,500))
            assertTrue("never-started registered HTTP is physically settled by same Stop",pending.closeKnown(System.nanoTime()/1_000_000+500))
        } finally { f.consumer.retire()?.let { assertTrue(f.consumer.stopCaptured(it, 500)) } }
    }
    @Test fun originalPhysicalEndNeverAcceptsAJoinedOperationLate() {
        var now=100L;var released=0;var created=0
        val operation=GeneralControlOperation({created++;error("inert cancelled operation must not create HTTP")},{now},200,4096,{true},{released++},false)
        assertFalse(operation.closeKnown(100));assertEquals(0,released)
        now=101
        assertTrue(operation.closeKnown(110));assertEquals(1,released);assertEquals(0,created)
        now=110
        assertFalse(operation.closeKnown(110));assertEquals(1,released)
    }
    companion object {
        internal fun controlOptions(f: AndroidGeneralServiceConsumerTest.Fixture) = org.olcbox.app.vpn.AndroidGeneralRuntimeOptions(
            f.options.protect, f.options.socksPort, f.options.socksUsername, f.options.socksPassword, f.options.dns,
            "tunnel-domain:rga.apollot.ru\ntunnel-domain:pan.gate-altas.tech\n" + f.options.directRules)
    }
}
