package com.limelight.computers;

import static org.junit.Assert.*;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.limelight.nvstream.http.ComputerDetails;

import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28)
public class ComputerDatabaseManagerTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
        context.deleteDatabase("computers4.db");
    }

    @Test
    public void additiveMigrationPreservesAllOldColumnsAndIsIdempotent() {
        try (SQLiteDatabase db = context.openOrCreateDatabase("computers4.db", 0, null)) {
            db.execSQL(
                    "CREATE TABLE Computers(UUID TEXT PRIMARY KEY, ComputerName TEXT NOT NULL,"
                        + " Addresses TEXT NOT NULL, MacAddress TEXT, ServerCert BLOB)");
            db.execSQL(
                    "INSERT INTO Computers VALUES('test-device','Test"
                        + " computer','{}','00:00:00:00:00:01',X'010203')");
            ComputerDatabaseManager.migrateLastUsed(db);
            ComputerDatabaseManager.migrateLastUsed(db);
            try (Cursor row = db.rawQuery("SELECT * FROM Computers", null)) {
                assertTrue(row.moveToFirst());
                assertEquals("test-device", row.getString(0));
                assertEquals("Test computer", row.getString(1));
                assertEquals("{}", row.getString(2));
                assertEquals("00:00:00:00:00:01", row.getString(3));
                assertArrayEquals(new byte[] {1, 2, 3}, row.getBlob(4));
                assertEquals(0, row.getLong(5));
                assertFalse(row.moveToNext());
            }
        }
    }

    private ComputerDetails host() {
        ComputerDetails c = new ComputerDetails();
        c.uuid = "test-device";
        c.name = "Test computer";
        return c;
    }

    @Test
    public void timestampSurvivesReopenAndBackgroundPollWithNoTimestamp() {
        ComputerDatabaseManager db = new ComputerDatabaseManager(context);
        ComputerDetails c = host();
        assertTrue(db.updateComputer(c));
        db.markUsed(c.uuid, 1234);
        db.close();
        db = new ComputerDatabaseManager(context);
        assertEquals(1234, db.getComputerByUUID(c.uuid).lastUsed);
        assertTrue(db.updateComputer(host()));
        assertEquals(1234, db.getComputerByUUID(c.uuid).lastUsed);
        db.close();
    }

    @Test
    public void timestampCannotMoveBackwardsOrCreateADeletedHost() {
        ComputerDatabaseManager db = new ComputerDatabaseManager(context);
        ComputerDetails c = host();
        db.updateComputer(c);
        db.markUsed(c.uuid, 1234);
        db.markUsed(c.uuid, 100);
        db.markUsed(c.uuid, -10);
        assertEquals(1234, db.getComputerByUUID(c.uuid).lastUsed);
        db.deleteComputer(c);
        db.markUsed(c.uuid, 2000);
        assertNull(db.getComputerByUUID(c.uuid));
        db.close();
    }

    @Test
    public void aNewHostDefaultsToNeverUsed() {
        ComputerDatabaseManager db = new ComputerDatabaseManager(context);
        ComputerDetails c = host();
        db.updateComputer(c);
        assertEquals(0, db.getComputerByUUID(c.uuid).lastUsed);
        db.close();
    }
    @Test
    public void partialUpdatesPreserveIndependentRoutesAcrossReopen() {
        ComputerDatabaseManager db = new ComputerDatabaseManager(context);
        ComputerDetails c = host();
        c.localAddress = new ComputerDetails.AddressTuple("192.168.4.27", 47989);
        c.remoteAddress = new ComputerDetails.AddressTuple("203.0.113.10", 47989);
        db.updateComputer(c);
        ComputerDetails manual = host();
        manual.manualAddress = new ComputerDetails.AddressTuple("torre", 47989);
        manual.localAddress = new ComputerDetails.AddressTuple("100.97.8.59", 47989);
        db.updateComputer(manual);
        db.updateComputer(host());
        db.close();
        db = new ComputerDatabaseManager(context);
        ComputerDetails saved = db.getComputerByUUID(c.uuid);
        assertEquals(c.localAddress, saved.localAddress);
        assertEquals(c.remoteAddress, saved.remoteAddress);
        assertEquals(manual.manualAddress, saved.manualAddress);
        assertEquals(1, db.getAllComputers().size());
        db.close();
    }

}
