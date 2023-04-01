package org.banka1.exchangeservice.services;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.banka1.exchangeservice.domains.dtos.order.OrderRequest;
import org.banka1.exchangeservice.domains.dtos.user.UserDto;
import org.banka1.exchangeservice.domains.entities.*;
import org.banka1.exchangeservice.domains.mappers.OrderMapper;
import org.banka1.exchangeservice.repositories.ForexRepository;
import org.banka1.exchangeservice.repositories.OrderRepository;
import org.banka1.exchangeservice.repositories.StockRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Random;

@Service
@Slf4j
public class OrderService {

    private final OrderRepository orderRepository;
    private final ForexRepository forexRepository;
    private final StockRepository stockRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();


    @Value("${user.service.endpoint}")
    private String userServiceUrl;


    public OrderService(OrderRepository orderRepository, ForexRepository forexRepository, StockRepository stockRepository) {
        this.orderRepository = orderRepository;
        this.forexRepository = forexRepository;
        this.stockRepository = stockRepository;
    }

    public Order makeOrder(OrderRequest orderRequest, String token) {
        UserDto userDto = getUserDtoFromUserService(token);
        Double expectedPrice = calculateThePrice(orderRequest.getListingType(),orderRequest.getSymbol(),orderRequest.getQuantity());

        Order order = new Order();
        order.setEmail(userDto.getEmail());
        order.setUserId(userDto.getId());
        order.setExpectedPrice(expectedPrice);

        if(userDto.getBankAccount().getDailyLimit() - expectedPrice < 0) order.setOrderStatus(OrderStatus.ON_HOLD);
        else order.setOrderStatus(OrderStatus.APPROVED);

        reduceDailyLimitForUser(token, userDto.getId(), expectedPrice);

        OrderMapper.INSTANCE.updateOrderFromOrderRequest(order, orderRequest);

        orderRepository.save(order);

        mockExecutionOfOrder(order);

        return order;
    }

    private Double calculateThePrice(ListingType listingType, String symbol, Integer quantity){
        if(listingType.equals(ListingType.FOREX)){
         Forex forex = forexRepository.findBySymbol(symbol);
         return forex.getExchangeRate() * quantity;
        } else if (listingType.equals(ListingType.STOCK)) {
            Stock stock = stockRepository.findBySymbol(symbol);
            return stock.getPrice() * quantity;
        }
        return 0.0;
    }

    private UserDto getUserDtoFromUserService(String token){
        String url = userServiceUrl + "/users/my-profile";
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", token)
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .build();

        UserDto userDto = null;
        try {
            HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            userDto = objectMapper.readValue(response.body(), UserDto.class);
        }catch (Exception e){
            e.printStackTrace();
        }

        return userDto;
    }

    private void reduceDailyLimitForUser(String token,Long userId, Double decreaseLimit){
        String url = userServiceUrl + "/users/reduce-daily-limit?userId=" + userId + "&decreaseLimit=" + decreaseLimit;
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", token)
                .method("GET", HttpRequest.BodyPublishers.noBody())
                .build();

        try {
            HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
        }catch (Exception e){
            e.printStackTrace();
        }
    }

    @Async
    public void mockExecutionOfOrder(Order order){
        // KREIRATI
        while(!order.getDone()){
            try {
                Thread.sleep(10000);
            } catch (Exception e){
                e.printStackTrace();
            }

            Random random = new Random();
            int quantity = random.nextInt(order.getRemainingQuantity() + 1);
            order.setRemainingQuantity(order.getRemainingQuantity() - quantity);

            if(order.getRemainingQuantity() == 0){
                order.setDone(true);
            }
        }
        orderRepository.save(order);
    }
}
