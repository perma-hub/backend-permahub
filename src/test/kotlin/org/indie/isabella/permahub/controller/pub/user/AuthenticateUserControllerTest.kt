package org.indie.isabella.permahub.controller.pub.user

import com.fasterxml.jackson.databind.ObjectMapper
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.impl.DefaultClock
import org.assertj.core.api.Assertions
import org.hamcrest.CoreMatchers
import org.indie.isabella.permahub.config.MongoInitializer
import org.indie.isabella.permahub.entity.User
import org.indie.isabella.permahub.entity.repository.UserRepository
import org.indie.isabella.permahub.model.http.request.UserData
import org.json.JSONObject
import org.junit.jupiter.api.*
import org.mockito.InjectMocks
import org.mockito.Mockito
import org.mockito.MockitoAnnotations
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.http.MediaType
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.ContextConfiguration
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.ResultActions
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders
import org.springframework.test.web.servlet.result.MockMvcResultMatchers
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.*

@SpringBootTest
@AutoConfigureMockMvc
@ContextConfiguration(initializers = [MongoInitializer::class])
@ActiveProfiles(value = ["test"])
@DisplayName("Authenticate Public User Controller Test")
class AuthenticateUserControllerTest {

    @Autowired
    private lateinit var userRepository: UserRepository

    private val objectMapper: ObjectMapper = ObjectMapper()

    @Value("\${permahub.public.frontend.url}")
    private lateinit var PUBLIC_FRONT_END_URL: String

    @Value("\${jwt.access.secret}")
    private lateinit var JWT_ACCESS_SECRET: String

    @Value("\${jwt.refresh.secret}")
    private lateinit var JWT_REFRESH_SECRET: String

    @SpyBean
    private lateinit var mockDefaultClock: DefaultClock

    @Autowired
    private lateinit var mockMvc: MockMvc

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Nested
    @DisplayName("Non existent email")
    inner class NonExistentEmail() {
        private lateinit var result: ResultActions

        @BeforeAll
        fun triggerEvent() {
            result = mockMvc.perform(
                MockMvcRequestBuilders
                    .post("/public/api/users/authenticate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            UserData(
                                "non_existent@mail.co",
                                "the_password"
                            )
                        )
                    )
                    .header("Origin", PUBLIC_FRONT_END_URL)
            )
        }

        @Test
        fun shouldReturn401() {
            result
                .andExpect(MockMvcResultMatchers.status().isUnauthorized)
                .andExpect(MockMvcResultMatchers.jsonPath("$.error.type", CoreMatchers.`is`("BadCredentialsException")))
                .andExpect(
                    MockMvcResultMatchers.jsonPath(
                        "$.error.message",
                        CoreMatchers.`is`("Invalid email or password")
                    )
                )
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Nested
    @DisplayName("Existent email wrong password")
    inner class ExistentEmailWrongPassword() {
        private lateinit var result: ResultActions

        @BeforeAll
        fun triggerEvent() {
            val user = User(
                "existing@client.co",
                BCryptPasswordEncoder().encode("password"),
                UUID.randomUUID()
            )
            user.verified = true
            userRepository.save(user)
            result = mockMvc.perform(
                MockMvcRequestBuilders
                    .post("/public/api/users/authenticate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            UserData(
                                "existing@client.co",
                                "wrong_password"
                            )
                        )
                    )
                    .header("Origin", PUBLIC_FRONT_END_URL)
            )
        }

        @AfterAll
        fun clearDb() {
            userRepository.deleteAll()
        }

        @Test
        fun shouldReturn401() {
            result
                .andExpect(MockMvcResultMatchers.status().isUnauthorized)
                .andExpect(MockMvcResultMatchers.jsonPath("$.error.type", CoreMatchers.`is`("BadCredentialsException")))
                .andExpect(
                    MockMvcResultMatchers.jsonPath(
                        "$.error.message",
                        CoreMatchers.`is`("Invalid email or password")
                    )
                )
        }
    }

    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Nested
    @DisplayName("Correct credentials but not verified")
    inner class CorrectCredentialsButNotVerified() {
        private lateinit var result: ResultActions

        @BeforeAll
        fun triggerEvent() {
            userRepository.save(
                User(
                    "existing@client.co",
                    BCryptPasswordEncoder().encode("password"),
                    UUID.randomUUID()
                )
            )
            result = mockMvc.perform(
                MockMvcRequestBuilders
                    .post("/public/api/users/authenticate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            UserData(
                                "existing@client.co",
                                "password"
                            )
                        )
                    )
                    .header("Origin", PUBLIC_FRONT_END_URL)
            )
        }

        @AfterAll
        fun clearDb() {
            userRepository.deleteAll()
        }

        @Test
        fun shouldReturn401() {
            result
                .andExpect(MockMvcResultMatchers.status().isUnauthorized)

                .andExpect(
                    MockMvcResultMatchers.jsonPath(
                        "$.error.type",
                        CoreMatchers.`is`("InternalAuthenticationServiceException")
                    )
                )
                .andExpect(
                    MockMvcResultMatchers.jsonPath(
                        "$.error.message",
                        CoreMatchers.`is`("Please verify your email")
                    )
                )
        }
    }


    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @Nested
    @DisplayName("Correct credentials and verified")
    inner class CorrectCredentialsAndVerified() {
        private lateinit var result: ResultActions

        val jwtAccessParser = Jwts.parser().setSigningKey(JWT_ACCESS_SECRET)
        val jwtRefreshParser = Jwts.parser().setSigningKey(JWT_REFRESH_SECRET)

        @BeforeAll
        fun triggerEvent() {

            val user = User("existing@client.co", BCryptPasswordEncoder(16).encode("password"), UUID.randomUUID())
            user.verified = true
            userRepository.save(user)
            val mockedDateTimeValue = LocalDateTime.of(2020, 2, 3, 7, 0)
            Mockito.`when`(mockDefaultClock.now())
                .thenReturn(Date.from(mockedDateTimeValue.atZone(ZoneId.systemDefault()).toInstant()))
            jwtAccessParser.setClock(mockDefaultClock)
            jwtRefreshParser.setClock(mockDefaultClock)
            result = mockMvc.perform(
                MockMvcRequestBuilders
                    .post("/public/api/users/authenticate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            UserData(
                                "existing@client.co",
                                "password"
                            )
                        )
                    )
                    .header("Origin", PUBLIC_FRONT_END_URL)
            )


        }

        @AfterAll
        fun clearDb() {
            userRepository.deleteAll()
        }

        @Test
        fun shouldReturn201() {
            result
                .andExpect(MockMvcResultMatchers.status().isCreated)
                .andExpect(
                    MockMvcResultMatchers.jsonPath(
                        "$.success.accessToken",
                        CoreMatchers.any(String::class.java)
                    )
                )
                .andExpect(
                    MockMvcResultMatchers.jsonPath(
                        "$.success.refreshToken",
                        CoreMatchers.any(String::class.java)
                    )
                )
                .andExpect(MockMvcResultMatchers.jsonPath("$.success.expiredAt", CoreMatchers.any(String::class.java)))

            val response = JSONObject(result.andReturn().response.contentAsString).getJSONObject("success")
            val accessToken = jwtAccessParser.parseClaimsJws(response["accessToken"].toString()).body
            val refreshToken = jwtRefreshParser.parseClaimsJws(response["refreshToken"].toString()).body

            Assertions.assertThat(accessToken.subject).isEqualTo("existing@client.co")
            Assertions.assertThat(accessToken.issuedAt)
                .isEqualTo(Date.from(LocalDateTime.of(2020, 2, 3, 7, 0).atZone(ZoneId.systemDefault()).toInstant()))
            Assertions.assertThat(accessToken.expiration)
                .isEqualTo(Date.from(LocalDateTime.of(2020, 2, 3, 9, 0).atZone(ZoneId.systemDefault()).toInstant()))

            Assertions.assertThat(refreshToken.subject).isEqualTo("existing@client.co")
            Assertions.assertThat(refreshToken.issuedAt)
                .isEqualTo(Date.from(LocalDateTime.of(2020, 2, 3, 7, 0).atZone(ZoneId.systemDefault()).toInstant()))
            Assertions.assertThat(refreshToken.expiration)
                .isEqualTo(Date.from(LocalDateTime.of(2020, 2, 4, 7, 0).atZone(ZoneId.systemDefault()).toInstant()))
            Assertions.assertThat(response["expiredAt"]).isEqualTo(
                LocalDateTime.of(2020, 2, 3, 9, 0).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_INSTANT)
            )
        }
    }
}