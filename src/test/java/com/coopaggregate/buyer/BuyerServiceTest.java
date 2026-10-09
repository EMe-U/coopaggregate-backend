package com.coopaggregate.buyer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BuyerServiceTest {

    private BuyerRepository repository;
    private BuyerService service;

    @BeforeEach
    void setUp() {
        repository = mock(BuyerRepository.class);
        service = new BuyerService(repository);
        when(repository.findByPhone(any())).thenReturn(Optional.empty());
        when(repository.save(any(Buyer.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0788123456", "250788123456", "+250788123456"})
    void phoneIsStoredAsPlus250(String phone) {
        BuyerResponse buyer = service.create(new NewBuyerRequest(" Kalisa Traders ", phone, "Trader"));

        assertEquals("+250788123456", buyer.phone());
        assertEquals("Kalisa Traders", buyer.name());
        assertEquals("Trader", buyer.type());
    }

    @Test
    void blankTypeIsStoredAsNull() {
        assertNull(service.create(new NewBuyerRequest("Kalisa Traders", "0788123456", " ")).type());
    }

    @Test
    void duplicatePhoneIsRejected() {
        Buyer existing = new Buyer();
        existing.setName("Mukamana Shop");
        when(repository.findByPhone("+250788123456")).thenReturn(Optional.of(existing));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> service.create(new NewBuyerRequest("Kalisa Traders", "0788123456", null)));

        assertEquals("Phone number +250788123456 is already used by buyer Mukamana Shop.", e.getMessage());
        verify(repository, never()).save(any());
    }
}
