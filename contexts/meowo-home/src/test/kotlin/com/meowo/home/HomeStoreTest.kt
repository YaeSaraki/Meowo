package com.meowo.home

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.UUID
import java.util.concurrent.TimeUnit

class HomeStoreTest {
    @TempDir lateinit var dir:Path
    private fun home(owner: UUID=UUID.randomUUID(),slot:Int=1)=Home(owner,slot,UUID.randomUUID(),"world",10.0,64.0,20.0,0f,0f,"家 $slot",0x91B7A2)
    private fun <T> java.util.concurrent.CompletableFuture<T>.await()=get(5,TimeUnit.SECONDS)
    @Test fun `legacy ten slot schema migrates and preserves journal above ten`() {
        val file=dir.resolve("legacy.db");val original=home();val ticket=UUID.randomUUID()
        Class.forName("org.sqlite.JDBC")
        java.sql.DriverManager.getConnection("jdbc:sqlite:$file").use { db ->
            db.createStatement().use { it.execute("CREATE TABLE homes(owner TEXT NOT NULL,slot INTEGER NOT NULL CHECK(slot BETWEEN 1 AND 10),data TEXT NOT NULL,ticket TEXT UNIQUE,previous TEXT,PRIMARY KEY(owner,slot))") }
            db.prepareStatement("INSERT INTO homes VALUES(?,?,?,?,?)").use { st ->
                st.setString(1,original.owner.toString());st.setInt(2,1);st.setString(3,original.encode());st.setString(4,ticket.toString());st.setString(5,null);st.executeUpdate()
            }
        }
        HomeStore(file).use { store ->
            assertEquals(listOf(ticket),store.pending().await());store.finish(ticket,true).await()
            val another=home(original.owner,35);val second=UUID.randomUUID()
            store.stage(another,null,second,40).await();store.finish(second,true).await()
            assertEquals(listOf(original,another),store.all().await())
        }
    }
    @Test fun `restart can roll back uncommitted creation and relocation without losing previous home`() {
        val file=dir.resolve("homes.db");val first=home();val t=UUID.randomUUID()
        HomeStore(file).use { it.stage(first,null,t,10).await();assertTrue(it.all().await().isEmpty()) }
        HomeStore(file).use { store ->
            assertEquals(listOf(t),store.pending().await());store.finish(t,false).await();assertTrue(store.all().await().isEmpty())
            val saved=UUID.randomUUID();store.stage(first,null,saved,10).await();store.finish(saved,true).await()
            store.stage(first.copy(x=999.0),first,t,10).await()
        }
        HomeStore(file).use { store -> store.finish(t,false).await();assertEquals(listOf(first),store.all().await()) }
    }
    @Test fun `committed journal recovers new home and free edits stay durable`() {
        val file=dir.resolve("homes.db");val h=home();val t=UUID.randomUUID()
        HomeStore(file).use { it.stage(h,null,t,10).await() }
        HomeStore(file).use { store ->
            store.finish(t,true).await();store.finish(t,true).await()
            val renamed=h.copy(name="新家",style="bowtie",visible=false,item="BELL")
            store.update(renamed,h).await();assertEquals(listOf(renamed),store.all().await())
            assertThrows(Exception::class.java) { store.update(h,h).await() }
            store.delete(renamed).await();assertTrue(store.all().await().isEmpty())
        }
    }
    @Test fun `ten home capacity is per player and optimistic writes reject stale pages`() {
        HomeStore(dir.resolve("homes.db")).use { store ->
            val owner=UUID.randomUUID()
            for(n in 1..10) { val h=home(owner,n);val t=UUID.randomUUID();store.stage(h,null,t,10).await();store.finish(t,true).await() }
            assertEquals(10,store.all().await().size)
            assertThrows(Exception::class.java) { store.stage(home(owner,1),null,UUID.randomUUID(),10).await() }
            val other=home();val t=UUID.randomUUID();store.stage(other,null,t,10).await();store.finish(t,true).await()
            assertEquals(11,store.all().await().size)
            assertThrows(Exception::class.java) { store.stage(home(UUID.randomUUID(),2),null,UUID.randomUUID(),1).await() }
        }
    }
}
