/*
 * Copyright 2016 Faisal Thaheem <faisal.ajmal@gmail.com>.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.computedsynergy.hurtrade.hurcpu.cpu;

import com.computedsynergy.hurtrade.sharedcomponents.amqp.AmqpBase;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.Quote;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.QuoteList;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.SourceQuote;
import com.computedsynergy.hurtrade.sharedcomponents.dataexchange.positions.Position;
import com.computedsynergy.hurtrade.sharedcomponents.models.pojos.SavedPosition;
import com.computedsynergy.hurtrade.sharedcomponents.models.pojos.User;
import com.computedsynergy.hurtrade.sharedcomponents.util.Constants;
import com.computedsynergy.hurtrade.sharedcomponents.util.HurUtil;
import com.computedsynergy.hurtrade.sharedcomponents.util.RedisUtil;
import com.google.gson.Gson;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import java.io.IOException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 *
 * @author Faisal Thaheem <faisal.ajmal@gmail.com>
 *
 * Subscribes to the rate queue and notifies the clients in case there is an
 * update after applying client specific spreads
 */
public class CommodityUpdateProcessor extends AmqpBase {
    
    //SavedPositioModel savedPositionModel = new sa

    public void init() throws Exception {
        
        super.setupAMQP();
        
        channel.queueDeclare(Constants.RATES_QUEUE_NAME, true, false, false, null);

        channel.basicConsume(Constants.RATES_QUEUE_NAME, false, "CommodityPricePump",
                new DefaultConsumer(channel) {
                    @Override
                    public void handleDelivery(String consumerTag,
                            Envelope envelope,
                            AMQP.BasicProperties properties,
                            byte[] body)
                    throws IOException {
                        String routingKey = envelope.getRoutingKey();
                        String contentType = properties.getContentType();
                        long deliveryTag = envelope.getDeliveryTag();

                        
                        SourceQuote quote = new Gson().fromJson(new String(body), SourceQuote.class);
                        processQuote(quote);

                        channel.basicAck(deliveryTag, false);
                    }
                });
    }

    private void processQuote(SourceQuote quote) {
        
        String clientExchangeName = HurUtil.getClientExchangeName(quote.getUser().getUseruuid());
        
        //introduce the spread as defined for this client for the symbols in the quote list
        Map<String, BigDecimal> userSpread = RedisUtil
                                                .getInstance()
                                                .getUserSpreadMap(RedisUtil.getUserSpreadMapName(quote.getUser().getUseruuid()));
        
        
        QuoteList sourceQuotes = quote.getQuoteList();
        QuoteList clientQuotes = new QuoteList();
        for(String k:sourceQuotes.keySet()){
            
            Quote sourceQuote = sourceQuotes.get(k);

            Quote q = null;
            //include propagation to client only if they are allowed this symbol
            if(userSpread.containsKey(k)){
                q = new Quote(
                        sourceQuote.bid,
                        sourceQuote.bid.add(userSpread.get(k)),
                        sourceQuote.quoteTime,
                        BigDecimal.ZERO,
                        sourceQuote.name
                );
                
            }else{
                q = new Quote(
                        sourceQuote.bid,
                        sourceQuote.ask,
                        sourceQuote.quoteTime,
                        BigDecimal.ZERO,
                        sourceQuote.name
                );
            }
            clientQuotes.put(k, q);
        }
        //process client's positions
        updateClientPositions(quote.getUser(), clientQuotes);
        
        //remove the quotes not allowed for this client
        for(String k:sourceQuotes.keySet()){
            if(!userSpread.containsKey(k)){
                clientQuotes.remove(k);
            }
        }
        
        String responseBody = new Gson().toJson(clientQuotes);
        //finally send out the quotes and updated positions to the client
        try{
            channel.basicPublish(clientExchangeName, "response", null, responseBody.getBytes());
        }catch(Exception ex){

            Logger.getLogger(CommodityUpdateProcessor.class.getName()).log(Level.SEVERE, null, ex);
        }

    }
    
    private void updateClientPositions(User user, QuoteList clientQuotes)
    {
        //get client positions
        Map<UUID, Position> positions = RedisUtil.getInstance().getUserPositions(user);
        
        for(Position p:positions.values()){
            p.processQuote(clientQuotes);
        }
        
        //set client positions
        String serializedPositions = RedisUtil.getInstance().setUserPositions(user, positions);
        
        //dump the client's positions to db
        SavedPosition p = new SavedPosition(user.getId(), serializedPositions);
        
    }
}
