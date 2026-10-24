package org.olcbox.app.data.datasource

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.olcbox.app.data.model.LocationBundleV4
import org.olcbox.app.data.repository.SubscriptionFetchFence
import org.olcbox.app.data.repository.SubscriptionFetchProxy
import org.olcbox.app.data.repository.SubscriptionOwnedFetch
import org.olcbox.app.data.repository.SubscriptionOwnedResponse

class GeneralOwnedSubscriptionFetchTest {
    @Test fun sameOwnedUrlUsesActualParserAndFencedCommitWithoutDefaultOrKtor(): Unit = runBlocking {
        val f=Fixture()
        try {
            assertTrue(f.repo.importText(URL,f.proxy))
            assertEquals(1,f.ownedReads);assertEquals(0,f.directReads);assertEquals(0,f.ktorReads)
            val entry=f.repo.getBundle().locations.single()
            assertEquals(URL,entry.subscriptionUrl)
            assertEquals(12L,entry.metadata!!.subscription!!.usedBytes)
            assertEquals(100L,entry.metadata!!.subscription!!.totalBytes)
            assertEquals(1,f.commits)
        } finally { f.client.close() }
    }
    @Test fun otherUrlMovedUrlAndLateDriftNeverCommitOrFallBack(): Unit = runBlocking {
        for(mode in 0..2){val f=Fixture();try{
            if(mode==1)f.headers=mapOf("new-url" to listOf("https://rga.apollot.ru/sub/other"))
            if(mode==2)f.afterFetch={f.live=false}
            assertFalse(f.repo.importText(if(mode==0) "$URL?other=1" else URL,f.proxy))
            assertEquals(0,f.commits);assertEquals(0,f.directReads);assertEquals(0,f.ktorReads)
            assertEquals(if(mode==0)0 else 1,f.ownedReads)
        }finally{f.client.close()}}
    }
    @Test fun ownedExceptionAndExplicitDeviceRefusalHaveNoDirectRecovery(): Unit = runBlocking {
        for(throwing in listOf(true,false)){val f=Fixture();try{
            f.throwFetch=throwing
            f.headers=mapOf("x-hwid-max-devices-reached" to listOf("true"))
            assertFalse(f.repo.importText(URL,f.proxy));assertEquals(0,f.commits);assertEquals(0,f.directReads);assertEquals(0,f.ktorReads)
        }finally{f.client.close()}}
    }
    private class Fixture {
        var live=true;var directReads=0;var ktorReads=0;var ownedReads=0;var commits=0
        var throwFetch=false;var afterFetch:()->Unit={}
        var headers=mapOf("subscription-userinfo" to listOf("upload=5; download=7; total=100; expire=0"))
        var bundle:LocationBundleV4?=null
        val source=object:LocationsDataSource{
            override suspend fun loadLocationBundle()=bundle
            override suspend fun saveLocationBundle(bundle:LocationBundleV4){this@Fixture.bundle=bundle}
            override suspend fun saveLocationBundleFenced(bundle:LocationBundleV4,fence:SubscriptionFetchFence):Boolean =
                fence.commit?.invoke {this@Fixture.bundle=bundle;commits++}==true
            override suspend fun loadLegacyLocations()=emptyList<Pair<String,String>>()
            override suspend fun loadLegacyActiveLocationId():String?=null
        }
        val client=HttpClient(MockEngine{directReads++;respond(BODY)})
        val repo=LocationsRepositoryImpl(source,client,partnerLinkResolver=null,
            proxyHttpClientFactory={ktorReads++;HttpClient(MockEngine{respond(BODY)})})
        val proxy=SubscriptionFetchProxy("127.0.0.1",23456,"synthetic","synthetic",
            SubscriptionFetchFence({it==URL},{live},{action->if(!live)false else{action();true}}),
            object:SubscriptionOwnedFetch{
                override suspend fun fetch(exactUrl:String,hwid:String,userAgent:String):SubscriptionOwnedResponse{
                    check(exactUrl==URL);check(hwid.isNotBlank());check(userAgent.isNotBlank());ownedReads++
                    if(throwFetch)throw java.io.IOException("controlled")
                    afterFetch();return SubscriptionOwnedResponse(200,headers,BODY)
                }
            })
    }
    companion object{
        const val URL="https://rga.apollot.ru/sub/owned-synthetic"
        const val BODY="vless://00000000-0000-0000-0000-000000000001@198.51.100.20:443?security=tls&type=tcp#Owned"
    }
}
