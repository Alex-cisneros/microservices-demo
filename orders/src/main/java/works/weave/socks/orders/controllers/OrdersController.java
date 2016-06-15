package works.weave.socks.orders.controllers;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.data.rest.webmvc.RepositoryRestController;
import org.springframework.hateoas.Resource;
import org.springframework.hateoas.mvc.TypeReferences;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import works.weave.socks.Shipment;
import works.weave.socks.accounts.entities.Address;
import works.weave.socks.accounts.entities.Card;
import works.weave.socks.accounts.entities.Customer;
import works.weave.socks.cart.entities.Item;
import works.weave.socks.orders.entities.CustomerOrder;
import works.weave.socks.orders.repositories.CustomerOrderRepository;
import works.weave.socks.orders.resources.NewOrderResource;
import works.weave.socks.orders.services.AsyncGetService;

import java.io.IOException;
import java.net.URI;
import java.util.Calendar;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RepositoryRestController
public class OrdersController {
    private final Logger LOG = LoggerFactory.getLogger(getClass());

    private final URI paymentUri = URI.create("http://payment/paymentAuth");
    private final URI shippingUri = URI.create("http://shipping/shipping");

    @Autowired
    private AsyncGetService asyncGetService;

    @Autowired
    private CustomerOrderRepository customerOrderRepository;

    @Value(value = "${http.timeout:5}")
    private long timeout;

    @ResponseStatus(HttpStatus.CREATED)
    @RequestMapping(path = "/orders", consumes = MediaType.APPLICATION_JSON_VALUE, method = RequestMethod.POST)
    public
    @ResponseBody
    CustomerOrder newOrder(@RequestBody NewOrderResource item) {
        try {
            LOG.debug("Starting calls");
            Future<Resource<Address>> addressFuture = asyncGetService.getResource(item.address, new TypeReferences.ResourceType<Address>() {
            });
            Future<Resource<Customer>> customerFuture = asyncGetService.getResource(item.customer, new TypeReferences.ResourceType<Customer>() {
            });
            Future<Resource<Card>> cardFuture = asyncGetService.getResource(item.card, new TypeReferences.ResourceType<Card>() {
            });
            Future<List<Item>> itemsFuture = asyncGetService.getDataList(item.items, new ParameterizedTypeReference<List<Item>>() {
            });
            LOG.debug("End of calls.");

            // Call payment service to make sure they've paid
            asyncGetService.postResource(paymentUri, "{}", new ParameterizedTypeReference<String>() {
            }).get(timeout, TimeUnit.SECONDS);

            // Ship
            String customerId = parseId(customerFuture.get(timeout, TimeUnit.SECONDS).getId().getHref());
            Future<Shipment> shipmentFuture = asyncGetService.postResource(shippingUri, new Shipment(customerId), new ParameterizedTypeReference<Shipment>() {
            });

            CustomerOrder order = new CustomerOrder(
                    null,
                    customerId,
                    customerFuture.get(timeout, TimeUnit.SECONDS).getContent(),
                    addressFuture.get(timeout, TimeUnit.SECONDS).getContent(),
                    cardFuture.get(timeout, TimeUnit.SECONDS).getContent(),
                    itemsFuture.get(timeout, TimeUnit.SECONDS),
                    shipmentFuture.get(timeout, TimeUnit.SECONDS),
                    Calendar.getInstance().getTime()
            );
            LOG.debug("Received data: " + order.toString());

            CustomerOrder savedOrder = customerOrderRepository.save(order);
            LOG.debug("Saved order: " + savedOrder);

            return savedOrder;
        } catch (TimeoutException e) {
            throw new IllegalStateException("Unable to create order due to timeout from one of the services.", e);
        } catch (InterruptedException | IOException | ExecutionException e) {
            throw new IllegalStateException("Unable to create order due to unspecified IO error.", e);
        }
    }

//    TODO: Add link to shipping
//    @RequestMapping(method = RequestMethod.GET, value = "/orders")
//    public @ResponseBody
//    ResponseEntity<?> getOrders() {
//        List<CustomerOrder> customerOrders = customerOrderRepository.findAll();
//
//        Resources<CustomerOrder> resources = new Resources<>(customerOrders);
//
//        resources.forEach(r -> r);
//
//        resources.add(linkTo(methodOn(ShippingController.class, CustomerOrder.getShipment::ge)).withSelfRel());
//
//        // add other links as needed
//
//        return ResponseEntity.ok(resources);
//    }

    private String parseId(String href) {
        Pattern idPattern = Pattern.compile("[\\w-]+$");
        Matcher matcher = idPattern.matcher(href);
        if (!matcher.find()) {
            throw new IllegalStateException("Could not parse user ID from: " + href);
        }
        return matcher.group(0);
    }
}
